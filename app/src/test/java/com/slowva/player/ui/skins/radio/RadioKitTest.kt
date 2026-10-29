package com.slowva.player.ui.skins.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioKitTest {

    @Test
    fun marqueeStaysPutWhenTheTextFits() {
        assertEquals(0f, marqueeShift(80f, 100f, 110f, 0.4f, 2000f), 0f)
    }

    @Test
    fun marqueeWrapsSeamlessly() {
        val period = 300f
        for (t in listOf(0f, 0.13f, 0.5f, 0.999f)) {
            val shift = marqueeShift(250f, 100f, period, t, 2000f)
            assertTrue("shift $shift at $t", shift >= 0f && shift < period)
        }
        // the loop ends on a whole lap, so t=1 lands where t=0 starts
        assertEquals(0f, marqueeShift(250f, 100f, period, 1f, 2000f) % period, 1e-3f)
        assertTrue(marqueeShift(250f, 100f, period, 0.05f, 2000f) > 0f)
    }

    @Test
    fun levelsStayInRangeAndLoop() {
        for (i in 0..6) {
            for (k in 0..100) assertTrue(level(k / 100f, i) in 0f..1f)
            assertEquals(level(0f, i), level(1f, i), 1e-3f)
        }
    }
}
