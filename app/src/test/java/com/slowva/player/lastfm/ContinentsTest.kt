package com.slowva.player.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinentsTest {
    private fun s(title: String, artist: String) = LastFm.Song(title, artist)

    @Test
    fun everyContinentHasCountriesAndTags() {
        Continent.entries.forEach {
            assertTrue(it.label, it.countries.isNotEmpty())
            assertTrue(it.label, it.tags.size >= 8)
        }
    }

    @Test
    fun smallCountryTopSongBeatsBigCountryTail() {
        val big = listOf(s("Big hit", "A") to 10_000L, s("Big tail", "B") to 2_000L)
        val small = listOf(s("Small hit", "C") to 50L)
        assertEquals(listOf("Big hit", "Small hit", "Big tail"), WorldCharts.mergeCharts(listOf(big, small), emptySet()).map { it.title })
    }

    @Test
    fun sharedSongsAddUpAndAreKeptOnce() {
        val one = listOf(s("Solo", "A") to 100L, s("Shared", "B") to 60L)
        val two = listOf(s("Other", "C") to 100L, s("Shared (Official Video)", "B feat. D") to 60L)
        val merged = WorldCharts.mergeCharts(listOf(one, two), emptySet())
        assertEquals(listOf("Shared", "Solo", "Other"), merged.map { it.title })
    }

    @Test
    fun hiddenArtistsAreLeftOutAndTheListStopsAt100() {
        val chart = (1..150).map { s("Song $it", if (it == 1) "Hidden One" else "Artist $it") to (1000L - it) }
        val merged = WorldCharts.mergeCharts(listOf(chart), setOf("hidden one"))
        assertEquals(100, merged.size)
        assertEquals("Song 2", merged.first().title)
    }

    @Test
    fun artistsTakeTurnsOnceEachRegionalFirst() {
        fun a(n: String) = LastFm.Artist(n, null)
        val merged = WorldCharts.mergeArtists(listOf(listOf(a("Drake"), a("Asake")), listOf(a("drake"), a("Rema"), a("Wegz"))), setOf("Drake"))
        assertEquals(listOf("Asake", "Rema", "Wegz", "Drake"), merged.map { it.name })
    }

    @Test
    fun regionalArtistsOutrankWorldwideStars() {
        val chart = listOf(LastFm.Song("Global hit", "Star") to 1000L, LastFm.Song("Local hit", "Hometown Band") to 600L, LastFm.Song("Other", "Someone") to 500L)
        val merged = WorldCharts.mergeCharts(listOf(chart), emptySet(), local = setOf("Hometown Band"), global = setOf("Star"))
        assertEquals(listOf("Local hit", "Other", "Global hit"), merged.map { it.title })
    }
}
