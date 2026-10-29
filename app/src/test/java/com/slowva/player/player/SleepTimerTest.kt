package com.slowva.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @Test fun fadeStartsTenSecondsBeforeTheEndAtTheCurrentSpeed() {
        assertFalse(SleepTimer.fadeDue(positionMs = 189_000, durationMs = 200_000, rate = 1f))
        assertTrue(SleepTimer.fadeDue(positionMs = 190_000, durationMs = 200_000, rate = 1f))
        // at double speed 20 s of track is 10 s of listening
        assertTrue(SleepTimer.fadeDue(positionMs = 180_000, durationMs = 200_000, rate = 2f))
        // unknown length never ends early
        assertFalse(SleepTimer.fadeDue(positionMs = 5_000, durationMs = 0, rate = 1f))
    }

    @Test fun minutesLeftRoundUp() {
        assertEquals(30L, SleepTimer.minutesLeft(endsAt = 30 * 60_000L, now = 0))
        assertEquals(1L, SleepTimer.minutesLeft(endsAt = 1_000, now = 0))
        assertEquals(0L, SleepTimer.minutesLeft(endsAt = 0, now = 5_000))
    }

    @Test fun fadeGoesFromFullToSilent() {
        assertEquals(1f, SleepTimer.fadeLevel(0f), 0f)
        assertEquals(0f, SleepTimer.fadeLevel(1f), 0f)
        assertTrue(SleepTimer.fadeLevel(0.5f) < 0.5f)
    }
}
