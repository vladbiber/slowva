package com.slowva.player.recommend

import com.slowva.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextTest {
    private fun t(id: String, title: String, artist: String) = Track(id, title, artist, "", "", null, 0L, isDemo = false)
    private fun s(id: String, title: String, artist: String) = Song(id, title, artist)

    @Test
    fun mixPrefersLibraryCopiesAndSkipsCurrentQueuedAndHidden() {
        val current = t("local1", "Song A", "Artist 1")
        val queued = listOf(t("local2", "Song B", "Artist 2"))
        val library = listOf(current, queued[0], t("local3", "Song C", "Artist 3"), t("vidD", "Song D", "Artist 4"))
        val mix = listOf(
            s("vidA", "Song A (Official Video)", "Artist 1"), // the song playing now
            s("vidB", "Song B", "Artist 2"), // already queued
            s("vidC", "Song C", "Artist 3"), // in the library as a local file
            s("vidD", "Something else", "Artist 4"), // in the library by id
            s("vidE", "Song E", "Hidden One feat. X"), // hidden artist
            s("vidF", "Song F", "Artist 5"), // online, not in the library
        )
        val out = UpNext.pick(current, queued, library, mix, hidden = setOf("Hidden One"))
        assertEquals(listOf("local3", "vidD", "vidF"), out.map { it.id })
    }

    @Test
    fun cutsToMax() {
        val mix = (1..10).map { s("v$it", "Song $it", "Artist $it") }
        assertEquals(3, UpNext.pick(t("x", "Seed", "Someone"), emptyList(), emptyList(), mix, emptySet(), max = 3).size)
    }
}
