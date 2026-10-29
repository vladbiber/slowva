package com.slowva.player.lastfm

import com.slowva.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteTest {
    @Test
    fun genreTagsCountFamilies() {
        assertEquals(listOf("hip hop", "rock"), Taste.genreTags(listOf("Rap", "ROCK", "Hip-hop", "trap", null, "news")))
    }

    @Test
    fun genreRowPutsMineFirstAndSkipsSameFamily() {
        val row = Taste.genreRow(listOf("hip hop"), listOf("rock", "hip-hop", "rap", "experimental", "rock"), n = 3)
        assertEquals(listOf("hip hop", "rock", "experimental"), row)
    }

    @Test
    fun myGenresWeighMore() {
        assertEquals(3, Taste.weight("hip-hop", listOf("hip hop")))
        assertEquals(1, Taste.weight("jazz", listOf("hip hop")))
    }

    @Test
    fun artistsMostPlayedFirstWithCover() {
        fun t(id: String, artist: String, cover: String?) = Track(id, "t$id", artist, "", "", cover, 0, false)
        val a = Taste.artists(listOf(t("1", "A", null), t("2", "B", "b.jpg"), t("3", "b", null), t("4", "Unknown artist", null)))
        assertEquals(listOf("B" to "b.jpg", "A" to null), a.map { it.name to it.image })
    }
}
