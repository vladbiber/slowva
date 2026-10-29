package com.slowva.player.player.fx

/** Slider limits for the effects; [t] is the "extreme range" setting, 0 normal (x1) .. 1 (x10). */
object FxRange {
    fun factor(t: Float) = 1f + 9f * t.coerceIn(0f, 1f)
    fun slow(t: Float) = (0.5f / factor(t))..(1.25f * factor(t)).coerceAtMost(8f)
    fun reverb(t: Float) = 0f..factor(t)
    fun bass(t: Float) = 0f..(12f * factor(t)).coerceAtMost(60f)
    fun width(t: Float) = 0f..(2f * factor(t))
    fun muffle(t: Float) = 0f..factor(t)
    fun rain(t: Float) = 0f..(1.5f * factor(t))
}
