package com.slowva.player.recommend

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenArtistsTest {
    private val songs = listOf("The Weeknd", "Dua Lipa", "the weeknd feat. Daft Punk", "Daft Punk", "THE WEEKND & Ariana Grande", "Lorde")

    @Test
    fun hiddenArtistsGoAndTheNextOnesFillTheGap() {
        val kept = HiddenArtists.filter(songs, setOf("The Weeknd")) { it }
        assertEquals(listOf("Dua Lipa", "Daft Punk", "Lorde"), kept)
        // a shelf of two is filled from further down, not left with holes
        assertEquals(listOf("Dua Lipa", "Daft Punk"), kept.take(2))
    }

    @Test
    fun nothingHiddenKeepsEverything() {
        assertEquals(songs, HiddenArtists.filter(songs, emptySet()) { it })
    }
}
