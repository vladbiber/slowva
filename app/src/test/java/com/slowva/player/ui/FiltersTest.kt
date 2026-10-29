package com.slowva.player.ui

import com.slowva.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun t(id: String, title: String, artist: String, album: String = "", ms: Long = 200_000, uri: String = "") =
        Track(id, title, artist, album, uri, null, ms, isDemo = false)

    private val a = t("1", "Bravo", "Lorde", "Melodrama", 120_000)
    private val b = t("s2", "alpha", "The Weeknd", "Lorde Covers", 250_000, uri = "spstream://2")
    private val c = t("3", "Charlie", "the weeknd feat. Daft Punk", ms = 400_000)
    private val d = t("4", "Delta", "Dua Lipa", ms = 0)
    private val all = listOf(a, b, c, d)
    private fun ids(l: List<Track>) = l.map { it.id }

    @Test
    fun defaultKeepsEverythingInOrder() {
        assertEquals(all, SongFilter().apply(all))
        assertFalse(SongFilter().active)
    }

    @Test
    fun matchPicksTheField() {
        assertEquals(listOf("1", "s2"), ids(SongFilter().apply(all, "lorde")))
        assertEquals(listOf("1"), ids(SongFilter(match = Match.ARTISTS).apply(all, "lorde")))
        assertEquals(listOf("s2"), ids(SongFilter(match = Match.ALBUMS).apply(all, "lorde")))
    }

    @Test
    fun downloadedLikedHiddenAndLength() {
        assertEquals(listOf("1", "3", "4"), ids(SongFilter(downloadedOnly = true).apply(all)))
        assertEquals(listOf("3"), ids(SongFilter(likedOnly = true).apply(all, liked = setOf("3"))))
        assertEquals(listOf("1", "4"), ids(SongFilter(hideHidden = true).apply(all, hidden = setOf("The Weeknd"))))
        // unknown length only shows with Any
        assertEquals(listOf("1"), ids(SongFilter(length = Length.SHORT).apply(all)))
        assertEquals(listOf("s2"), ids(SongFilter(length = Length.MID).apply(all)))
        assertEquals(listOf("3"), ids(SongFilter(length = Length.LONG).apply(all)))
    }

    @Test
    fun sorts() {
        assertEquals(listOf("s2", "1", "3", "4"), ids(SongFilter(sort = SongSort.TITLE).apply(all)))
        assertEquals(listOf("4", "1", "s2", "3"), ids(SongFilter(sort = SongSort.ARTIST).apply(all)))
        // newest first; ids not in the list go last
        assertEquals(listOf("3", "s2", "1", "4"), ids(SongFilter(sort = SongSort.ADDED).apply(all, added = listOf("1", "s2", "3"))))
        // unplayed keep their custom order after the played ones
        assertEquals(listOf("4", "1", "s2", "3"), ids(SongFilter(sort = SongSort.PLAYS).apply(all, plays = listOf("4", "1"))))
    }

    @Test
    fun encodeRoundTripsAndBadInputFallsBack() {
        val f = SongFilter(sort = SongSort.PLAYS, downloadedOnly = true, hideHidden = true)
        assertEquals(f, SongFilter.decode(f.encode()))
        assertEquals(SongFilter(), SongFilter.decode(null))
        assertEquals(SongFilter(), SongFilter.decode("NOPE,1"))
    }
}
