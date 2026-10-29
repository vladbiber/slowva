package com.slowva.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonsTest {
    @Test
    fun playStarSoftensFromPausedToPlayingToPressed() {
        assertEquals(0f, playMorph(playing = false, pressed = false), 0f)
        assertTrue(playMorph(playing = true, pressed = false) > 0f)
        assertTrue(playMorph(playing = true, pressed = true) > playMorph(playing = true, pressed = false))
        assertEquals(playMorph(playing = false, pressed = true), playMorph(playing = true, pressed = true), 0f)
    }

    @Test
    fun waveEasesWithoutOvershoot() {
        var amp = 0f
        repeat(120) { amp = easeToward(amp, 1f, 1f / 60, 0.18f); assertTrue(amp in 0f..1f) }
        assertEquals(1f, amp, 0.01f)
        assertEquals(0.5f, easeToward(0.5f, 1f, 0f, 0.18f), 0f)
        repeat(120) { amp = easeToward(amp, 0f, 1f / 60, 0.18f) }
        assertTrue(amp < 0.01f)
    }
}
