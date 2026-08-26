package com.slowva.player.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small allocation-free FFT used by the in-app and home-screen CAVA views.
 * It analyzes the PCM that already passed through slowva's DSP, so the bars
 * represent what is actually sent to the phone speaker.
 */
class SpectrumAnalyzer(
    private val sampleRate: Int = LocalPlayer.RATE,
    private val fftSize: Int = 1024,
    private val barCount: Int = BAR_COUNT,
) {
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val smoothed = FloatArray(barCount)

    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    fun analyze(stereo: ShortArray, frames: Int): FloatArray {
        val available = frames.coerceAtMost(stereo.size / 2)
        val start = (available - fftSize).coerceAtLeast(0)
        for (i in 0 until fftSize) {
            val frame = start + i
            val sample = if (frame < available) {
                (stereo[frame * 2].toInt() + stereo[frame * 2 + 1].toInt()) / 65536f
            } else {
                0f
            }
            val window = (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
            real[i] = sample * window
            imaginary[i] = 0f
        }

        fft()

        val minHz = 45.0
        val maxHz = 16_000.0.coerceAtMost(sampleRate / 2.0)
        val ratio = (maxHz / minHz).pow(1.0 / barCount)
        for (bar in 0 until barCount) {
            val lowHz = minHz * ratio.pow(bar.toDouble())
            val highHz = minHz * ratio.pow((bar + 1).toDouble())
            val lowBin = max(1, (lowHz * fftSize / sampleRate).toInt())
            val highBin = max(lowBin + 1, (highHz * fftSize / sampleRate).toInt())
                .coerceAtMost(fftSize / 2)

            var energy = 0.0
            var bins = 0
            for (bin in lowBin until highBin) {
                val re = real[bin].toDouble()
                val im = imaginary[bin].toDouble()
                energy += re * re + im * im
                bins++
            }
            val magnitude = sqrt(energy / bins.coerceAtLeast(1)) / (fftSize / 2.0)
            val db = 20.0 * log10(magnitude.coerceAtLeast(1e-7))
            val target = ((db + 76.0) / 62.0).coerceIn(0.015, 1.0).toFloat()
            smoothed[bar] = if (target > smoothed[bar]) {
                smoothed[bar] * 0.30f + target * 0.70f
            } else {
                smoothed[bar] * 0.82f + target * 0.18f
            }
        }
        return smoothed.copyOf()
    }

    fun clear(): FloatArray {
        smoothed.fill(0f)
        return smoothed.copyOf()
    }

    private fun fft() {
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = ti
            }
        }

        var len = 2
        while (len <= fftSize) {
            val angle = -2.0 * PI / len
            val wLenReal = cos(angle).toFloat()
            val wLenImaginary = sin(angle).toFloat()
            var offset = 0
            while (offset < fftSize) {
                var wReal = 1f
                var wImaginary = 0f
                for (i in 0 until len / 2) {
                    val even = offset + i
                    val odd = even + len / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImaginary
                    val oddImaginary = real[odd] * wImaginary + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = wReal * wLenReal - wImaginary * wLenImaginary
                    wImaginary = wReal * wLenImaginary + wImaginary * wLenReal
                    wReal = nextReal
                }
                offset += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BAR_COUNT = 24
    }
}
