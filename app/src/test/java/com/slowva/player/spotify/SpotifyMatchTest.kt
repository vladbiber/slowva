package com.slowva.player.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyMatchTest {
    private fun t(id: String, name: String, artist: String, ms: Long = 200_000, playable: Boolean = true) =
        SpTrack(id, name, artist.split(", ").map { SpArtist("", it) }, "", ms, null, playable)

    @Test
    fun picksTheStudioCutOverLiveAndRemix() {
        val hits = listOf(
            t("live", "Creep (Live)", "Radiohead"),
            t("remix", "Creep - Remix", "Radiohead"),
            t("studio", "Creep", "Radiohead"),
        )
        assertEquals("studio", SpotifyMatch.best(hits, "Radiohead", "Creep")?.id)
    }

    @Test
    fun aLiveRequestGetsTheLiveVersion() {
        val hits = listOf(t("studio", "Creep", "Radiohead"), t("live", "Creep (Live)", "Radiohead"))
        assertEquals("live", SpotifyMatch.best(hits, "Radiohead", "Creep (Live)")?.id)
    }

    @Test
    fun theArtistMustMatchAnyCreditedArtist() {
        val hits = listOf(t("cover", "Creep", "Some Cover Band"), t("feat", "Creep", "Guest, Radiohead"))
        assertEquals("feat", SpotifyMatch.best(hits, "Radiohead", "Creep")?.id)
        assertNull(SpotifyMatch.best(listOf(t("cover", "Creep", "Some Cover Band")), "Radiohead", "Creep"))
    }

    @Test
    fun durationRulesOutOtherSongsAndPicksTheClosest() {
        val hits = listOf(t("long", "Intro", "X", ms = 400_000), t("near", "Intro", "X", ms = 182_000), t("exact", "Intro", "X", ms = 180_500))
        assertEquals("exact", SpotifyMatch.best(hits, "X", "Intro", 180_000)?.id)
        assertNull(SpotifyMatch.best(listOf(t("long", "Intro", "X", ms = 400_000)), "X", "Intro", 180_000))
    }

    @Test
    fun remasterTagsAndUnplayableSongs() {
        assertEquals("rm", SpotifyMatch.best(listOf(t("rm", "Karma Police - Remastered 2017", "Radiohead")), "Radiohead", "Karma Police")?.id)
        assertNull(SpotifyMatch.best(listOf(t("gone", "Karma Police", "Radiohead", playable = false)), "Radiohead", "Karma Police"))
    }

    @Test
    fun anyArtistWhenNoneIsGiven() {
        assertEquals("a", SpotifyMatch.best(listOf(t("a", "Halo", "Beyonce")), null, "Halo")?.id)
    }

    @Test
    fun interleaveTakesOneFromEachWithoutRepeats() {
        val a = listOf(t("1", "a", "A"), t("2", "b", "A"), t("3", "c", "A"))
        val b = listOf(t("2", "b", "A"), t("4", "d", "B"))
        assertEquals(listOf("1", "2", "4", "3"), SpotifyMatch.interleave(listOf(a, b), emptySet()).map { it.id })
        assertEquals(listOf("2", "4", "3"), SpotifyMatch.interleave(listOf(a, b), setOf("1")).map { it.id })
    }

    @Test
    fun quotesAreStrippedFromFilters() {
        assertEquals("\"Guns N  Roses\"", SpotifyMatch.quoted("Guns N\" Roses"))
    }
}
