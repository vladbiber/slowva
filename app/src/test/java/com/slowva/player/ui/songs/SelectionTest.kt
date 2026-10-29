package com.slowva.player.ui.songs

import com.slowva.player.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private fun t(id: String) = Track(id, id, "a", "b", "", null, 0L, isDemo = false)
    private val list = listOf(t("1"), t("2"), t("3"))

    @Test
    fun togglingAddsAndRemovesAndTheLastOneEndsSelecting() {
        var s = Selection().toggle(list[0], SongContext.Library, list)
        assertTrue(s.isSelecting(SongContext.Library))
        s = s.toggle(list[1], SongContext.Library, list)
        assertEquals(listOf("1", "2"), s.tracks.map { it.id })
        s = s.toggle(list[0], SongContext.Library, list).toggle(list[1], SongContext.Library, list)
        assertFalse(s.active)
        assertEquals(Selection(), s)
    }

    @Test
    fun anotherListStartsOver() {
        val s = Selection().toggle(list[0], SongContext.Library, list).toggle(list[1], SongContext.Playlist("x"), list)
        assertEquals(SongContext.Playlist("x"), s.context)
        assertEquals(listOf("2"), s.tracks.map { it.id })
        assertFalse(s.isSelecting(SongContext.Library))
    }

    @Test
    fun selectAllTakesTheWholeListOnce() {
        val s = Selection().toggle(list[1], SongContext.Favorites, list).selectAll()
        assertEquals(listOf("2", "1", "3"), s.tracks.map { it.id })
        assertTrue(s.has("3"))
    }
}
