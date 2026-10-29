package com.slowva.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayCounterTest {

    /** Plays [ms] of [id] from [from] in 250 ms updates; returns how many plays were counted. */
    private fun PlayCounter.play(id: String, from: Long, ms: Long, duration: Long): Int =
        (0..ms step 250).count { update(id, from + it, duration, playing = true) }

    @Test fun countsAfterThirtySecondsOnce() {
        val c = PlayCounter()
        assertEquals(0, c.play("a", 0, 29_000, 200_000))
        assertEquals(1, c.play("a", 29_250, 60_000, 200_000))
    }

    @Test fun shortTrackCountsAtHalf() {
        assertEquals(1, PlayCounter().play("a", 0, 20_000, 40_000))
        assertEquals(0, PlayCounter().play("a", 0, 19_000, 40_000))
    }

    @Test fun seekingAheadIsNotListening() {
        val c = PlayCounter()
        c.play("a", 0, 5_000, 200_000)
        assertEquals(0, c.play("a", 150_000, 10_000, 200_000))
    }

    @Test fun pausedTimeAndRestartsAreHandled() {
        val c = PlayCounter()
        assertEquals(0, (0..40_000L step 250).count { c.update("a", 0, 200_000, playing = false) })
        assertEquals(1, c.play("a", 0, 40_000, 200_000))
        // repeat one: back to the start counts again
        assertEquals(1, c.play("a", 0, 40_000, 200_000))
        assertEquals(1, c.play("b", 0, 40_000, 200_000))
    }
}
