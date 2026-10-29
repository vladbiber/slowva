package com.slowva.player.player.sound

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.slowva.player.player.fx.R128Meter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

data class Measured(val lufs: Float, val peak: Float)

/** Decodes a whole local file once and measures its integrated loudness. Runs on a background thread. */
object LoudnessScan {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_DURATION_US = 40L * 60 * 1_000_000

    suspend fun measure(context: Context, uri: String): Measured? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.startsWith(ASSET)) {
                context.assets.openFd(uri.removePrefix(ASSET)).use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            } else {
                ex.setDataSource(context, Uri.parse(uri), null)
            }
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val fmt = ex.getTrackFormat(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION) && fmt.getLong(MediaFormat.KEY_DURATION) > MAX_DURATION_US) return null
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            var meter = R128Meter(rate, channels)
            var work = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    meter = R128Meter(rate, channels)
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val samples = info.size / if (floatPcm) 4 else 2
                    if (work.size < samples) work = FloatArray(samples)
                    if (floatPcm) for (k in 0 until samples) work[k] = buf.float
                    else for (k in 0 until samples) work[k] = buf.short / 32768f
                    meter.add(work, samples / channels.coerceAtLeast(1))
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val lufs = meter.integrated() ?: return null
            return Measured(lufs.toFloat(), meter.peak)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private const val ASSET = "file:///android_asset/"
}
