package com.slowva.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import com.slowva.player.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Decodes a local file with MediaCodec into 16-bit stereo, at the file's own
 * sample rate. [read] fills a buffer and returns the frame count, 0 at the end.
 */
internal class Decoder(ctx: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    val sampleRate: Int
    private var channels: Int
    private var floatPcm = false
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    private var pending: ShortArray? = null
    private var pendingAt = 0
    /** presentation time of the last decoded buffer */
    var positionUs = 0L
        private set

    init {
        extractor.setDataSource(ctx, uri, null)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalStateException("no audio track")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun seek(ms: Long) {
        extractor.seekTo(ms * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false; outputDone = false
        pending = null
        positionUs = ms * 1000
    }

    fun read(out: ShortArray): Int {
        val maxFrames = out.size / 2
        while (true) {
            pending?.let { p ->
                val frames = minOf(maxFrames, (p.size - pendingAt) / 2)
                System.arraycopy(p, pendingAt, out, 0, frames * 2)
                pendingAt += frames * 2
                if (pendingAt >= p.size) pending = null
                return frames
            }
            if (outputDone) return 0
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 5000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    positionUs = info.presentationTimeUs
                    pending = toStereo(buf)
                    pendingAt = 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (pending!!.isEmpty()) pending = null
                }
            }
        }
    }

    private fun toStereo(buf: java.nio.ByteBuffer): ShortArray {
        val ch = channels.coerceAtLeast(1)
        return if (floatPcm) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            ShortArray(frames * 2).also { o ->
                for (i in 0 until frames) {
                    val l = fb.get(i * ch); val r = if (ch > 1) fb.get(i * ch + 1) else l
                    o[i * 2] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    o[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            ShortArray(frames * 2).also { o ->
                for (i in 0 until frames) {
                    val l = sb.get(i * ch); val r = if (ch > 1) sb.get(i * ch + 1) else l
                    o[i * 2] = l; o[i * 2 + 1] = r
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

/** Plays local files through the same effect chain as Spotify playback. */
class FilePlayer(private val ctx: Context, private val fx: Fx) {

    data class State(val track: Track, val playing: Boolean, val positionMs: Long, val durationMs: Long)

    val state = MutableStateFlow<State?>(null)
    var onFinished: (() -> Unit)? = null
    var onSpectrum: ((FloatArray) -> Unit)? = null
    @Volatile var volume = 1f
    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var seekMs = -1L

    @Synchronized
    fun start(track: Track, fromMs: Long = 0, startPaused: Boolean = false) {
        generation++
        val run = generation
        paused = startPaused
        seekMs = if (fromMs > 0) fromMs else -1
        state.value = State(track, !startPaused, fromMs, track.durationMs.toLong())
        Thread({ pump(track, run) }, "slowva-file").apply { isDaemon = true; start() }
    }

    fun setPaused(value: Boolean) {
        paused = value
        state.value = state.value?.copy(playing = !value)
    }

    fun seek(ms: Long) {
        seekMs = ms.coerceAtLeast(0)
        state.value = state.value?.copy(positionMs = ms.coerceAtLeast(0))
    }

    @Synchronized
    fun stop() {
        generation++
        state.value = null
    }

    @SuppressLint("MissingPermission")
    private fun pump(track: Track, run: Int) {
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        val dec = try { Decoder(ctx, Uri.parse(track.uri)) } catch (e: Exception) {
            Log.w(TAG, "cannot open ${track.name}: ${e.message}")
            if (run == generation) { state.value = null; onFinished?.invoke() }
            return
        }
        val rate = dec.sampleRate
        val dsp = Dsp(fx, rate.toFloat())
        dsp.reset()
        val analyzer = SpectrumAnalyzer(sampleRate = rate)
        val out = AudioOut(rate)
        val inShorts = ShortArray(CHUNK * 2)
        val outShorts = ShortArray(dsp.maxOutFrames(CHUNK) * 2)
        val outBytes = ByteArray(outShorts.size * 2)
        var lastUi = 0L
        var finished = false
        try {
            out.play()
            while (run == generation) {
                if (paused) {
                    if (out.isPlaying) out.pause()
                    Thread.sleep(30)
                    continue
                }
                if (!out.isPlaying) out.play()
                val s = seekMs
                if (s >= 0) { seekMs = -1; dec.seek(s); out.pause(); out.flush(); out.play() }
                val frames = dec.read(inShorts)
                if (frames == 0) { finished = true; break }
                val n = dsp.process(inShorts, frames, outShorts)
                var o = 0
                for (i in 0 until n * 2) {
                    val v = outShorts[i].toInt()
                    outBytes[o] = (v and 0xFF).toByte(); outBytes[o + 1] = ((v shr 8) and 0xFF).toByte()
                    o += 2
                }
                out.setVolume(volume)
                out.write(outBytes, o) { run == generation && seekMs < 0 }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUi > 200 && run == generation) {
                    lastUi = now
                    state.value = state.value?.copy(positionMs = dec.positionUs / 1000, playing = true)
                    onSpectrum?.invoke(analyzer.analyze(outShorts, n))
                }
            }
            // let the tail play out before moving on
            if (finished) Thread.sleep(300)
        } catch (e: Exception) {
            Log.w(TAG, "file pump stopped: ${e.message}")
        } finally {
            out.release()
            dec.close()
            onSpectrum?.invoke(analyzer.clear())
        }
        if (finished && run == generation) onFinished?.invoke()
    }

    companion object {
        private const val TAG = "slowva-file"
        private const val CHUNK = 2048

        /** Decodes the whole file to raw s16le stereo for reverse mode; returns the sample rate. */
        suspend fun decodeAll(ctx: Context, track: Track, outFile: File): Result<Int> = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(outFile.path + ".tmp")
                Decoder(ctx, Uri.parse(track.uri)).use { dec ->
                    val buf = ShortArray(CHUNK * 2)
                    val bytes = ByteArray(buf.size * 2)
                    BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { os ->
                        while (true) {
                            val n = dec.read(buf)
                            if (n == 0) break
                            for (i in 0 until n * 2) {
                                val v = buf[i].toInt()
                                bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                            }
                            os.write(bytes, 0, n * 4)
                        }
                    }
                    if (!tmp.renameTo(outFile)) error("could not store decoded file")
                    dec.sampleRate
                }
            }
        }
    }
}
