package com.slowva.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import com.slowva.player.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Reverse playback: decode the whole track to PCM, then read it back to front through the same Dsp. */
class ReversePlayer(private val ctx: Context, private val fx: Fx) {

    data class State(
        val track: Track,
        val preparing: Boolean,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    val state = MutableStateFlow<State?>(null)
    var onFinished: (() -> Unit)? = null
    var onSpectrum: ((FloatArray) -> Unit)? = null

    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var seekFrame = -1L
    @Volatile var volume = 1f
    @Volatile private var rate = RATE

    private val dir get() = File(ctx.cacheDir, "reverse").apply { mkdirs() }

    fun preparing(track: Track) {
        stop()
        state.value = State(track, true, false, track.durationMs.toLong(), track.durationMs.toLong())
    }

    /** Decodes [track] to raw s16le stereo, reusing a cached copy when it is complete. */
    suspend fun decode(track: Track): Result<File> = withContext(Dispatchers.IO) {
        val out = File(dir, "${track.id.filter { it.isLetterOrDigit() }}.s16")
        val expected = track.durationMs.toLong() * RATE * 4 / 1000
        val tolerance = 5L * RATE * 4
        if (out.length() > RATE * 4 && (expected == 0L || out.length() in (expected - tolerance)..(expected + tolerance))) {
            out.setLastModified(System.currentTimeMillis())
            return@withContext Result.success(out)
        }
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libslowvareverse.so")
        if (!bin.exists()) return@withContext Result.failure(IllegalStateException("reverse decoder missing from the APK"))
        val tmp = File(dir, out.name + ".tmp")
        tmp.delete()
        val log = File(ctx.cacheDir, "reverse.log")
        val p = runCatching {
            ProcessBuilder(bin.absolutePath, File(ctx.cacheDir, "librespot").absolutePath, track.uri, tmp.absolutePath, "320")
                .apply {
                    val ipv4 = File(ctx.applicationInfo.nativeLibraryDir, "libslowvaipv4.so")
                    if (ipv4.isFile) environment()["LD_PRELOAD"] = ipv4.absolutePath
                }
                .directory(ctx.cacheDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        }.getOrElse { return@withContext Result.failure(it) }
        if (!p.waitFor(240, TimeUnit.SECONDS)) {
            p.destroy(); tmp.delete()
            return@withContext Result.failure(IllegalStateException("decoding took too long"))
        }
        if (p.exitValue() != 0 || tmp.length() < RATE * 4) {
            tmp.delete()
            val why = runCatching { log.readLines().lastOrNull { it.isNotBlank() } }.getOrNull()
            return@withContext Result.failure(IllegalStateException("decoder exit ${p.exitValue()}: ${why ?: "no output"}"))
        }
        if (!tmp.renameTo(out)) return@withContext Result.failure(IllegalStateException("could not store decoded track"))
        prune()
        Result.success(out)
    }

    /** Local files decode on the phone; returns the PCM file and its sample rate. */
    suspend fun decodeLocal(track: Track): Result<Pair<File, Int>> {
        val name = track.id.filter { it.isLetterOrDigit() }
        val out = File(dir, "$name.s16")
        val rateFile = File(dir, "$name.rate")
        val known = rateFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (out.length() > 0 && known != null) {
            out.setLastModified(System.currentTimeMillis())
            return Result.success(out to known)
        }
        return FilePlayer.decodeAll(ctx, track, out).map { sr ->
            rateFile.writeText(sr.toString())
            prune()
            out to sr
        }
    }

    /** about 10 MB per minute of audio, so only the last few tracks stay */
    private fun prune() {
        dir.listFiles { f -> f.name.endsWith(".s16") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    @Synchronized
    fun start(track: Track, file: File, fromMs: Long? = null, sampleRate: Int = RATE.toInt()) {
        stop()
        rate = sampleRate.toLong()
        val run = ++generation
        paused = false
        val total = file.length() / 4
        val from = fromMs?.let { it * rate / 1000 } ?: total
        seekFrame = from.coerceIn(0, total)
        state.value = State(track, false, true, seekFrame * 1000 / rate, total * 1000 / rate)
        Thread({ pump(file, run) }, "slowva-reverse").apply { isDaemon = true; start() }
    }

    fun setPaused(value: Boolean) {
        paused = value
        state.value = state.value?.copy(playing = !value)
    }

    fun seek(ms: Long) {
        seekFrame = (ms.coerceAtLeast(0) * rate / 1000)
        state.value = state.value?.copy(positionMs = ms.coerceAtLeast(0))
    }

    @Synchronized
    fun stop() {
        generation++
        state.value = null
    }

    @SuppressLint("MissingPermission")
    private fun pump(file: File, run: Int) {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        val dsp = Dsp(fx, rate.toFloat())
        dsp.reset()
        val analyzer = SpectrumAnalyzer(sampleRate = rate.toInt())
        val minBuf = AudioTrack.getMinBufferSize(rate.toInt(), AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate.toInt())
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, 64 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        out.play()

        val raw = ByteArray(CHUNK * 4)
        val inShorts = ShortArray(CHUNK * 2)
        val outShorts = ShortArray(dsp.maxOutFrames(CHUNK) * 2)
        val outBytes = ByteArray(outShorts.size * 2)
        var pos = seekFrame
        var lastUi = 0L
        var finished = false
        try {
            RandomAccessFile(file, "r").use { f ->
                while (run == generation) {
                    if (paused) {
                        if (out.playState == AudioTrack.PLAYSTATE_PLAYING) out.pause()
                        Thread.sleep(30)
                        continue
                    }
                    if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                    val s = seekFrame
                    if (s >= 0) { pos = s; seekFrame = -1 }
                    if (pos <= 0) { finished = true; break }
                    val from = (pos - CHUNK).coerceAtLeast(0)
                    val frames = (pos - from).toInt()
                    f.seek(from * 4)
                    f.readFully(raw, 0, frames * 4)
                    // newest frame first
                    for (i in 0 until frames) {
                        val src = (frames - 1 - i) * 4
                        inShorts[i * 2] = ((raw[src].toInt() and 0xFF) or (raw[src + 1].toInt() shl 8)).toShort()
                        inShorts[i * 2 + 1] = ((raw[src + 2].toInt() and 0xFF) or (raw[src + 3].toInt() shl 8)).toShort()
                    }
                    pos = from
                    val n = dsp.process(inShorts, frames, outShorts)
                    var o = 0
                    for (i in 0 until n * 2) {
                        val v = outShorts[i].toInt()
                        outBytes[o] = (v and 0xFF).toByte()
                        outBytes[o + 1] = ((v shr 8) and 0xFF).toByte()
                        o += 2
                    }
                    out.setVolume(volume)
                    var written = 0
                    while (written < o && run == generation && seekFrame < 0) {
                        val w = out.write(outBytes, written, o - written)
                        if (w <= 0) break
                        written += w
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 200 && run == generation) {
                        lastUi = now
                        state.value = state.value?.copy(positionMs = pos * 1000 / rate, playing = true)
                        onSpectrum?.invoke(analyzer.analyze(outShorts, n))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "reverse pump stopped: ${e.message}")
        } finally {
            runCatching { out.stop() }
            runCatching { out.release() }
            onSpectrum?.invoke(analyzer.clear())
        }
        if (finished && run == generation) {
            state.value = state.value?.copy(positionMs = 0, playing = false)
            onFinished?.invoke()
        }
    }

    companion object {
        private const val TAG = "slowva-reverse"
        const val RATE = 44100L
        private const val CHUNK = 2048
        private const val KEEP = 4
    }
}
