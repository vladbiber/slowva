package com.slowva.player.ui.wheel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** Timing for one spin; the reduced one is for "remove animations". */
data class SpinTiming(val cruiseSpeed: Float, val cruiseSec: Float, val decelSec: Float, val settleSec: Float) {
    companion object {
        val Normal = SpinTiming(cruiseSpeed = 640f, cruiseSec = 0.9f, decelSec = 2.0f, settleSec = 0.32f)
        val Reduced = SpinTiming(cruiseSpeed = 720f, cruiseSec = 0.3f, decelSec = 0.6f, settleSec = 0.12f)
    }
}

/** Wheel angle in degrees clockwise (0 = slice 0's edge under the needle) and the needle. */
@Stable
class WheelMotion {
    var angle by mutableFloatStateOf(0f)
    /** needle tip sideways push, in disc radii; + is clockwise */
    var needle by mutableFloatStateOf(0f)
    var spinning by mutableStateOf(false)
    var velocity = 0f
    var slices = 1
    private var needleV = 0f
    private var lastSlice = -1
    private var lastTickNs = 0L

    /** local angle under the needle, 0..360 */
    fun under(a: Float = angle): Float = ((-a) % 360f + 360f) % 360f
    fun sliceAt(a: Float = angle): Int = min(floor(under(a) / (360f / slices)).toInt(), slices - 1)

    /** pins push the needle aside, a spring brings it back */
    fun stepNeedle(dt: Float) {
        val s = 360f / slices
        val pinW = min(s * 0.4f, 10f)
        val u = under() % s
        val dir = sign(velocity)
        val push = when {
            slices < 2 -> 0f
            dir > 0 && u < pinW -> (pinW - u) / pinW * MAX_PUSH
            dir < 0 && s - u < pinW -> -(pinW - (s - u)) / pinW * MAX_PUSH
            else -> 0f
        }
        val k = 900f; val c = 14f
        needleV += (-k * needle - c * needleV) * dt
        needle += needleV * dt
        if (push > 0f && needle < push || push < 0f && needle > push) { needle = push; needleV = 0f }
    }

    /** true when a slice boundary passed since the last frame; ticks are at most ~40/s */
    fun crossed(nowNs: Long): Boolean {
        val i = sliceAt()
        val changed = lastSlice != -1 && i != lastSlice
        lastSlice = i
        if (!changed || nowNs - lastTickNs < 24_000_000L) return false
        lastTickNs = nowNs
        return true
    }

    fun resetTicks() { lastSlice = sliceAt() }

    companion object { const val MAX_PUSH = 0.07f }
}

/** Cruise while [prepare] runs, then brake onto the chosen slot. Returns the slot landed on. */
suspend fun WheelMotion.spin(
    target: Int,
    v0: Float,
    timing: SpinTiming,
    rnd: () -> Float,
    prepare: suspend (Int) -> Int,
    onTick: () -> Unit,
): Int = coroutineScope {
    spinning = true
    val dir = if (v0 < 0f) -1f else 1f
    val cruise = timing.cruiseSpeed * (0.92f + 0.16f * rnd())
    velocity = if (abs(v0) > 1f) v0 else 0f
    resetTicks()
    val ready = async { prepare(target) }

    // spin up (or settle from the fling) to cruise speed
    var t = 0f
    val cruiseFor = timing.cruiseSec + 0.2f * rnd()
    frames { dt, now ->
        t += dt
        velocity += (dir * cruise - velocity) * (1f - exp(-dt / 0.22f))
        advance(velocity * dt, dt, now, onTick)
        t < cruiseFor || !ready.isCompleted
    }
    val winner = ready.await()

    // a random spot inside the slice, kept away from the pins
    val s = 360f / slices
    val local = (winner + 0.22f + 0.56f * rnd()) * s
    val overshoot = dir * s * (0.04f + 0.08f * rnd())
    val v = max(abs(velocity), 60f)
    val decel = timing.decelSec * (0.9f + 0.2f * rnd())
    // aim for a 2.4 power curve, then bend the curve so the duration holds
    val want = v * decel / 2.4f
    val end = landing(angle, -local, dir, max(want - 180f, 30f))
    val start = angle
    val dist = abs(end + overshoot - start)
    val p = (v * decel / dist).coerceIn(1.6f, 4f)
    val dur = p * dist / v
    t = 0f
    frames { dt, now ->
        t = min(t + dt, dur)
        val f = 1f - (1f - t / dur).pow(p)
        val a = start + dir * dist * f
        velocity = dir * v * (1f - t / dur).pow(p - 1f)
        advance(a - angle, dt, now, onTick)
        t < dur
    }

    // settle back off the overshoot
    val from = angle
    t = 0f
    val back = timing.settleSec
    frames { dt, now ->
        t = min(t + dt, back)
        val e = t / back
        val a = from + (end - from) * (1f - (1f - e) * (1f - e) * (1f - e))
        velocity = -dir * 1f
        advance(a - angle, dt, now, onTick)
        t < back
    }
    velocity = 0f
    // let the needle come to rest
    t = 0f
    frames { dt, _ -> t += dt; stepNeedle(dt); t < 0.5f && (abs(needle) > 0.0005f || t < 0.1f) }
    needle = 0f
    angle %= 360f
    spinning = false
    winner
}

/** after a drag that wasn't a fling: let the needle swing back */
suspend fun WheelMotion.relax() {
    velocity = 0f
    var t = 0f
    frames { dt, _ -> t += dt; stepNeedle(dt); t < 0.6f && (abs(needle) > 0.0005f || t < 0.05f) }
    needle = 0f
}

private fun WheelMotion.advance(da: Float, dt: Float, now: Long, onTick: () -> Unit) {
    angle += da
    stepNeedle(dt)
    if (crossed(now)) onTick()
}

/** First angle past [from] + [min] in direction [dir] that is congruent to [goal] mod 360. */
private fun landing(from: Float, goal: Float, dir: Float, min: Float): Float {
    val base = from + dir * min
    val g = ((goal % 360f) + 360f) % 360f
    val b = ((base % 360f) + 360f) % 360f
    val d = if (dir > 0) ((g - b) + 360f) % 360f else -(((b - g) + 360f) % 360f)
    return base + d
}

private suspend inline fun frames(crossinline step: (dt: Float, now: Long) -> Boolean) {
    var last = 0L
    while (true) {
        val go = withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            step(dt, now)
        }
        if (!go) break
    }
}
