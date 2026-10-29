package com.slowva.player.ui.wheel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LuckyWheelTest {
    private val pool = (1..30).map { WheelItem("g$it", "Genre $it", "", null, it) }

    @Test
    fun winnerSitsOnItsSlotAmongDistinctSlices() {
        val w = luckyWheel(pool, { 1 }, Random(1))!!
        assertEquals(WheelPicker.MAX_SLICES, w.slices.size)
        assertEquals(w.slices.size, w.slices.map { it.id }.distinct().size)
        assertEquals(30, w.poolSize)
    }

    @Test
    fun weightedGenresWinMoreOftenButNotAlways() {
        val rnd = Random(7)
        val mine = setOf("g1", "g2", "g3")
        val wins = (1..3000).count { luckyWheel(pool, { if (it.id in mine) 3 else 1 }, rnd)!!.let { w -> w.slices[w.winner].id in mine } }
        // 9 of 36 weight units: about a quarter, against a tenth unweighted
        assertTrue("wins=$wins", wins in 600..900)
    }

    @Test
    fun emptyPoolGivesNoWheel() = assertNull(luckyWheel(emptyList(), { 1 }, Random(1)))
}
