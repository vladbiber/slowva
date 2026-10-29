package com.slowva.player.player.fx

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.slowva.player.player.sound.PcmFormat
import com.slowva.player.player.sound.SignalPath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs [EffectChain] on 16-bit PCM inside ExoPlayer. Mono comes out as stereo,
 * surround is folded down to stereo, any sample rate works because the effects
 * are built for the real rate. With nothing on, mono and stereo pass bit for bit.
 */
@UnstableApi
class FxAudioProcessor(
    private val fx: Fx,
    private val onSpectrum: (FloatArray) -> Unit = {},
) : BaseAudioProcessor() {

    private var chain: EffectChain? = null
    private var limiter: Limiter? = null
    private var inChannels = 2
    private var rate = 44100
    private var work = FloatArray(0)
    private var analyzer: SpectrumAnalyzer? = null
    private var shorts = ShortArray(0)
    private var lastSpectrum = 0L
    private var mixL = floatArrayOf(1f, 0f)
    private var mixR = floatArrayOf(0f, 1f)
    private var seed = 0x2545F491

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // anything unexpected plays untouched instead of failing the whole track
        val ok = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount >= 1
        SignalPath.decoderOut.value = PcmFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding, ok)
        if (!ok) return AudioProcessor.AudioFormat.NOT_SET
        inChannels = inputAudioFormat.channelCount
        buildDownmix(inChannels)
        if (chain == null || rate != inputAudioFormat.sampleRate) {
            rate = inputAudioFormat.sampleRate
            chain = EffectChain(fx, rate.toFloat()).also { it.reset() }
            limiter = Limiter(rate.toFloat())
            analyzer = SpectrumAnalyzer(sampleRate = rate)
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    /** Stereo gains per input channel, Android order FL FR FC LFE BL BR (BC) (SL SR). LFE is dropped. */
    private fun buildDownmix(channels: Int) {
        val roles = when (channels) {
            1 -> intArrayOf(MONO)
            2 -> intArrayOf(FL, FR)
            3 -> intArrayOf(FL, FR, FC)
            4 -> intArrayOf(FL, FR, BL, BR)
            5 -> intArrayOf(FL, FR, FC, BL, BR)
            6 -> intArrayOf(FL, FR, FC, LFE, BL, BR)
            7 -> intArrayOf(FL, FR, FC, LFE, BL, BR, BC)
            else -> intArrayOf(FL, FR, FC, LFE, BL, BR, SL, SR)
        }
        mixL = FloatArray(channels); mixR = FloatArray(channels)
        for (k in 0 until minOf(channels, roles.size)) {
            when (roles[k]) {
                MONO -> { mixL[k] = 1f; mixR[k] = 1f }
                FL -> mixL[k] = 1f
                FR -> mixR[k] = 1f
                FC -> { mixL[k] = MINUS_3DB; mixR[k] = MINUS_3DB }
                BL, SL -> mixL[k] = MINUS_3DB
                BR, SR -> mixR[k] = MINUS_3DB
                BC -> { mixL[k] = 0.5f; mixR[k] = 0.5f }
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val c = chain ?: return
        val lim = limiter ?: return
        val frames = inputBuffer.remaining() / (2 * inChannels)
        if (frames == 0) return
        if (work.size < frames * 2) { work = FloatArray(frames * 2); shorts = ShortArray(frames * 2) }
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = replaceOutputBuffer(frames * 4)
        if (inChannels <= 2 && c.idle) {
            // true bypass, the limiter only keeps its lookahead filled for a clean switch back
            for (i in 0 until frames) {
                val l = src.short
                val r = if (inChannels == 2) src.short else l
                out.putShort(l); out.putShort(r)
                shorts[i * 2] = l; shorts[i * 2 + 1] = r
                work[i * 2] = l / 32768f; work[i * 2 + 1] = r / 32768f
            }
            lim.process(work, maxOf(0, frames - lim.latency), frames)
        } else {
            val ml = mixL; val mr = mixR
            for (i in 0 until frames) {
                var l = 0f; var r = 0f
                for (k in 0 until inChannels) {
                    val s = src.short / 32768f
                    l += s * ml[k]; r += s * mr[k]
                }
                work[i * 2] = l; work[i * 2 + 1] = r
            }
            if (!c.idle) {
                c.process(work, frames)
                // one NaN would stay in the filter feedback forever and mute everything after it
                if (work.anyNaN(frames * 2)) {
                    chain = EffectChain(fx, rate.toFloat()).also { it.reset() }
                    for (i in 0 until frames * 2) if (work[i].isNaN()) work[i] = 0f
                }
            }
            lim.process(work, 0, frames)
            for (i in 0 until frames * 2) {
                // TPDF dither of +-1 LSB, then round
                val d = rnd() - rnd()
                val v = Math.round(work[i] * 32768f + d).coerceIn(-32768, 32767).toShort()
                shorts[i] = v
                out.putShort(v)
            }
        }
        out.flip()
        val now = System.nanoTime() / 1_000_000
        if (now - lastSpectrum > 70) {
            lastSpectrum = now
            analyzer?.let { onSpectrum(it.analyze(shorts, frames)) }
        }
    }

    /** xorshift32, uniform in [0, 1) */
    private fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / 16777216f)
    }

    override fun onFlush() {
        chain?.reset()
        limiter?.reset()
    }

    override fun onReset() {
        chain = null
        limiter = null
        analyzer?.let { onSpectrum(it.clear()) }
    }

    private companion object {
        const val MONO = 0; const val FL = 1; const val FR = 2; const val FC = 3; const val LFE = 4
        const val BL = 5; const val BR = 6; const val BC = 7; const val SL = 8; const val SR = 9
        const val MINUS_3DB = 0.70710677f
    }
}

/**
 * ExoPlayer's processor chain with the order slowva uses: speed first (Sonic,
 * pitch follows speed), then every effect, so vinyl and rain are never slowed.
 */
@UnstableApi
class FxProcessorChain(fx: Fx, onSpectrum: (FloatArray) -> Unit) : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val effects = FxAudioProcessor(fx, onSpectrum)

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(sonic, effects)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long = sonic.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = 0
}

private fun FloatArray.anyNaN(n: Int): Boolean {
    for (i in 0 until n) if (this[i].isNaN()) return true
    return false
}
