package com.slowva.player.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMixerTest {
    private fun s(id: String, title: String, artist: String) = Song(id, title, artist)

    @Test
    fun versionsOfTheSameSongAreOneWork() {
        val orig = s("a", "Blinding Lights", "The Weeknd")
        assertTrue(RadioMixer.sameWork(orig, s("b", "Blinding Lights (Sped Up)", "nightcore guy")))
        assertTrue(RadioMixer.sameWork(orig, s("c", "Blinding Lights - Live", "The Weeknd")))
        assertTrue(RadioMixer.sameWork(orig, s("d", "Blinding Lights slowed + reverb", "someone")))
        assertTrue(RadioMixer.sameWork(orig, s("e", "Blinding Lights", "The Weeknd feat. X")))
        assertTrue(RadioMixer.sameWork(orig, s("h", "The Weeknd - Blinding Lights (Official Video)", "The Weeknd")))
        // video titles name the artist first; two songs by one artist stay apart
        assertFalse(RadioMixer.sameWork(s("i", "Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley"), s("j", "Rick Astley - Together Forever", "Rick Astley")))
        // same title, different song by a different artist
        assertFalse(RadioMixer.sameWork(s("f", "Hello", "Adele"), s("g", "Hello", "Lionel Richie")))
    }

    @Test
    fun cleanDropsSeedRepeatsAndVersionsAndPrefersUnplayed() {
        val seed = s("seed", "Song A", "Artist 1")
        val out = RadioMixer.clean(
            known = listOf(seed),
            songs = listOf(
                seed,
                s("1", "Song A (Lyrics)", "Artist 1"),
                s("2", "Song B", "Artist 2"),
                s("2", "Song B", "Artist 2"),
                s("3", "Song C", "Artist 3"),
                s("4", "Song B (Karaoke Version)", "Karaoke Co"),
            ),
            played = setOf("2"),
        )
        assertEquals(listOf("3", "2"), out.map { it.id })
    }

    @Test
    fun noMoreThanTwoInARowBySameArtist() {
        val out = RadioMixer.spread(
            listOf(s("1", "a", "X"), s("2", "b", "X"), s("3", "c", "X"), s("4", "d", "Y"), s("5", "e", "X")),
        )
        assertEquals(listOf("1", "2", "4", "3", "5"), out.map { it.id })
    }

    @Test
    fun spreadKeepsLeftoversWhenOnlyOneArtistRemains() {
        val out = RadioMixer.spread(listOf(s("1", "a", "X"), s("2", "b", "X"), s("3", "c", "X")))
        assertEquals(listOf("1", "2", "3"), out.map { it.id })
    }

    @Test
    fun mixTakesTurnsAndDedupes() {
        val out = RadioMixer.mix(
            listOf(
                listOf(s("1", "One", "A"), s("2", "Two", "B")),
                listOf(s("1", "One", "A"), s("3", "Three", "C")),
                listOf(s("4", "Four", "D")),
            ),
            played = emptySet(),
        )
        assertEquals(listOf("1", "4", "2", "3"), out.map { it.id })
    }

    @Test
    fun leadArtistKeepsTheFirstCredit() {
        assertEquals("The Weeknd", RadioMixer.leadArtist("The Weeknd, Playboi Carti"))
        assertEquals("Daft Punk", RadioMixer.leadArtist("Daft Punk feat. Pharrell"))
        assertEquals("Adele", RadioMixer.leadArtist("Adele - Topic"))
        assertEquals("the weeknd", RadioMixer.artistKey("The Weeknd & Ariana Grande"))
    }
}
