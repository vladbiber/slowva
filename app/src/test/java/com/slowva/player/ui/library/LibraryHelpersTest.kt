package com.slowva.player.ui.library

import com.slowva.player.data.Track
import com.slowva.player.ui.libraryAlbums
import com.slowva.player.ui.libraryArtists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHelpersTest {
    private fun t(id: String, artist: String, album: String) = Track(id, "Song $id", artist, album, "", null, 1000L, isDemo = false)

    @Test fun artistMatchesWholeNamesOnly() {
        assertTrue(artistMatches("Daft Punk", "daft punk"))
        assertTrue(artistMatches("Calvin Harris, Dua Lipa", "Dua Lipa"))
        assertTrue(artistMatches("Drake feat. Rihanna", "Rihanna"))
        assertTrue(artistMatches("Kashi - Topic", "Kashi"))
        assertFalse(artistMatches("Yeah Yeah Yeahs", "Ye"))
        assertFalse(artistMatches("Anyone", ""))
    }

    @Test fun albumsNeedTwoSongsAndARealName() {
        val tracks = listOf(
            t("1", "A", "First"), t("2", "A", "First"),
            t("3", "B", "Single"),
            t("4", "C", "Imported"), t("5", "C", "Imported"),
        )
        val albums = libraryAlbums(tracks)
        assertEquals(listOf("First"), albums.map { it.title })
        assertEquals("A", albums.single().artist)
    }

    @Test fun artistsMostSongsFirst() {
        val tracks = listOf(t("1", "B", "x"), t("2", "A", "x"), t("3", "A, B", "x"), t("4", "Unknown artist", "x"))
        assertEquals(listOf("A" to 2, "B" to 1), libraryArtists(tracks))
    }
}
