package com.slowva.player.player.fx

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Stereo-linked lookahead peak limiter. Below [CEILING] it only delays the audio.
 * Held minimum plus a moving average over the lookahead window means the gain is
 * already down when a peak leaves the delay, so nothing passes the ceiling.
 */
internal class Limiter(sampleRate: Float) {
    /** delay in frames, the output is the input this many frames late */
    val latency = max(1, (LOOKAHEAD_S * sampleRate).toInt())
    private val win = latency + 1
    private val delayL = FloatArray(win)
    private val delayR = FloatArray(win)
    private val avgBuf = FloatArray(win)
    // one spare slot: the newest value goes in before the oldest is dropped
    private val qVal = FloatArray(win + 1)
    private val qAt = IntArray(win + 1)
    private var qHead = 0
    private var qSize = 0
    private var n = 0
    private var pos = 0
    private var avgSum = 0.0
    private var gain = 1f
    private val release = (1.0 - exp(-1.0 / (RELEASE_S * sampleRate))).toFloat()

    init { reset() }

    fun reset() {
        delayL.fill(0f); delayR.fill(0f); avgBuf.fill(1f)
        avgSum = win.toDouble()
        qHead = 0; qSize = 0; n = 0; pos = 0
        gain = 1f
    }

    /** Limits interleaved stereo frames [from, to) of [buf] in place. */
    fun process(buf: FloatArray, from: Int, to: Int) {
        for (i in from until to) {
            val l = buf[i * 2]
            val r = buf[i * 2 + 1]
            val peak = max(abs(l), abs(r))
            val need = if (peak > CEILING) CEILING / peak else 1f
            // sliding minimum of the needed gain over the window
            while (qSize > 0 && qVal[(qHead + qSize - 1) % (win + 1)] >= need) qSize--
            val tail = (qHead + qSize) % (win + 1)
            qVal[tail] = need; qAt[tail] = n; qSize++
            while (n - qAt[qHead] >= win) { qHead = (qHead + 1) % (win + 1); qSize-- }
            val held = qVal[qHead]
            avgSum += held - avgBuf[pos]
            avgBuf[pos] = held
            val target = (avgSum / win).toFloat()
            gain = if (target < gain) target else gain + (target - gain) * release
            delayL[pos] = l; delayR[pos] = r
            if (++pos >= win) pos = 0
            n++
            buf[i * 2] = delayL[pos] * gain
            buf[i * 2 + 1] = delayR[pos] * gain
        }
    }

    companion object {
        const val CEILING = 0.96605f      // -0.3 dBFS
        const val LOOKAHEAD_S = 0.002f
        const val RELEASE_S = 0.08
    }
}
