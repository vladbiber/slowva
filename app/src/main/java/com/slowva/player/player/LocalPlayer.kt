package com.slowva.player.player

import android.content.Context
import android.os.SystemClock
import android.os.Process as AndroidProcess
import android.util.Log
import android.net.wifi.WifiManager
import com.slowva.player.auth.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * The local Spotify Connect device.
 *
 * librespot is shipped inside the APK as lib*.so — Android extracts those into
 * nativeLibraryDir, which since API 29 is the only place an app is still
 * allowed to exec a file from. It runs with `--backend pipe`, so raw s16le
 * 44.1 kHz stereo arrives on its stdout; we push that through [Dsp] and out to
 * an AudioTrack. Same pipeline as spotc, minus pacat.
 */
class LocalPlayer(private val ctx: Context, val fx: Fx) {

    enum class Status { STOPPED, STARTING, RUNNING, FAILED }
    enum class FailureKind { AUTH, PREMIUM, NETWORK, OTHER }

    val status = MutableStateFlow(Status.STOPPED)
    val lastError = MutableStateFlow<String?>(null)
    val failureKind = MutableStateFlow<FailureKind?>(null)
    val spectrum = MutableStateFlow(FloatArray(SpectrumAnalyzer.BAR_COUNT))

    @Volatile private var proc: Process? = null
    @Volatile private var pump: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    val deviceName: String = "slowva"

    private val binary: File
        get() = File(ctx.applicationInfo.nativeLibraryDir, "liblibrespot.so")

    val isRunning: Boolean get() = proc?.isAlive == true

    /** [accessToken] is only needed until librespot has cached its credentials. */
    @Synchronized
    fun start(accessToken: String?, preferAccessToken: Boolean = false) {
        if (isRunning) return
        val run = ++generation
        status.value = Status.STARTING
        lastError.value = null
        failureKind.value = null

        val cache = File(ctx.cacheDir, "librespot").apply { mkdirs() }
        val bin = binary
        if (!bin.exists()) {
            fail("librespot missing from the APK (${bin.absolutePath})", FailureKind.OTHER)
            return
        }
        bin.setExecutable(true, false)

        val cmd = mutableListOf(
            bin.absolutePath,
            "--name", deviceName,
            "--device-type", "smartphone",
            "--backend", "pipe",
            "--format", "S16",
            "--dither", "none",
            "--bitrate", "320",
            "--cache", cache.absolutePath,
            "--disable-audio-cache",
            "--initial-volume", "100",
        )
        if (accessToken != null && (preferAccessToken || !SessionToken.available(ctx))) {
            cmd += listOf("--access-token", accessToken)
        }

        acquireMulticast()
        val p = try {
            ProcessBuilder(cmd).apply {
                val ipv4 = File(ctx.applicationInfo.nativeLibraryDir, "libslowvaipv4.so")
                if (ipv4.isFile) environment()["LD_PRELOAD"] = ipv4.absolutePath
            }
                .directory(ctx.cacheDir)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            releaseMulticast()
            fail("could not start librespot: ${e.message}", FailureKind.OTHER)
            return
        }
        proc = p

        Thread({ drainLog(p, run) }, "librespot-log").apply { isDaemon = true }.start()
        pump = Thread({ pumpAudio(p, run) }, "slowva-audio").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        generation++
        proc?.destroy()
        proc = null
        pump = null
        releaseMulticast()
        spectrum.value = FloatArray(SpectrumAnalyzer.BAR_COUNT)
        if (status.value != Status.FAILED) status.value = Status.STOPPED
    }

    private fun fail(message: String, kind: FailureKind, run: Int? = null) {
        if (run != null && run != generation) return
        Log.e(TAG, message)
        lastError.value = message
        failureKind.value = kind
        status.value = Status.FAILED
    }

    private fun pumpAudio(p: Process, run: Int) {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        val dsp = Dsp(fx)
        val analyzer = SpectrumAnalyzer()
        dsp.reset()

        val track = AudioOut(RATE)
        track.play()

        val raw = ByteArray(CHUNK_FRAMES * 4)
        val inShorts = ShortArray(CHUNK_FRAMES * 2)
        val outShorts = ShortArray(dsp.maxOutFrames(CHUNK_FRAMES) * 2)
        val outBytes = ByteArray(outShorts.size * 2)
        val stdout = p.inputStream

        var carry = 0
        var lastSpectrumAt = 0L
        try {
            while (run == generation) {
                val n = stdout.read(raw, carry, raw.size - carry)
                if (n < 0) break
                val total = carry + n
                val got = total - (total % 4)          // whole frames only
                carry = total - got
                if (got == 0) continue
                val frames = got / 4
                var b = 0
                for (i in 0 until frames * 2) {
                    inShorts[i] = ((raw[b].toInt() and 0xFF) or (raw[b + 1].toInt() shl 8)).toShort()
                    b += 2
                }
                val outFrames = dsp.process(inShorts, frames, outShorts)
                if (status.value == Status.STARTING && run == generation) {
                    status.value = Status.RUNNING
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastSpectrumAt >= 70L) {
                    spectrum.value = analyzer.analyze(outShorts, outFrames)
                    lastSpectrumAt = now
                }
                var o = 0
                for (i in 0 until outFrames * 2) {
                    val s = outShorts[i].toInt()
                    outBytes[o] = (s and 0xFF).toByte()
                    outBytes[o + 1] = ((s shr 8) and 0xFF).toByte()
                    o += 2
                }
                track.write(outBytes, o) { run == generation }
                // keep the 1-3 bytes of a split frame for the next read
                for (k in 0 until carry) raw[k] = raw[got + k]
            }
        } catch (e: Exception) {
            if (run == generation) Log.w(TAG, "audio pump stopped: ${e.message}")
        } finally {
            track.release()
            if (run == generation) {
                // a pump that died leaves librespot alive but unheard; end it so the watchdog restarts it
                runCatching { p.destroy() }
                spectrum.value = analyzer.clear()
                releaseMulticast()
                if (status.value != Status.FAILED) status.value = Status.STOPPED
            }
        }
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        multicastLock = wifi.createMulticastLock("slowva-connect").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticast() {
        multicastLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        multicastLock = null
    }

    /** Keeps librespot's stderr from filling its pipe, and remembers failures. */
    private fun drainLog(p: Process, run: Int) {
        val log = File(ctx.cacheDir, "librespot.log")
        try {
            FileOutputStream(log, true).bufferedWriter().use { out ->
                out.appendLine("--- slowva local player start ---")
                p.errorStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    out.flush()
                    when {
                        line.contains("Premium account required", true) -> fail(
                            "Spotify Premium is required for playback on this phone",
                            FailureKind.PREMIUM,
                            run,
                        )
                        line.contains("Bad credentials", true) ||
                            line.contains("Could not validate credentials", true) -> fail(
                            "Spotify rejected cached player credentials",
                            FailureKind.AUTH,
                            run,
                        )
                        line.contains("Extra verification required", true) -> fail(
                            "Spotify requires an extra account verification — sign in again",
                            FailureKind.AUTH,
                            run,
                        )
                        line.contains("Login failed", true) -> fail(
                            line.substringAfter("Login failed with reason:", "Spotify rejected the player sign-in")
                                .trim()
                                .let { "Spotify sign-in failed: $it" },
                            FailureKind.AUTH,
                            run,
                        )
                        line.contains("Authentication is not possible", true) -> fail(
                            "Cached Spotify player credentials are missing or damaged",
                            FailureKind.AUTH,
                            run,
                        )
                        line.contains("could not initialize spirc", true) -> fail(
                            "Spotify Connect could not start: ${line.substringAfter("could not initialize spirc:").trim()}",
                            if (line.contains("permission", true)) FailureKind.AUTH
                            else FailureKind.NETWORK,
                            run,
                        )
                    }
                    if (line.contains("ERROR", true)) Log.w(TAG, line)
                }
            }
        } catch (e: Exception) {
            if (run == generation) Log.w(TAG, "log drain: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "slowva"
        const val RATE = 44100
        const val CHUNK_FRAMES = 2048
    }
}
