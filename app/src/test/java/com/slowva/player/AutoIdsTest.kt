package com.slowva.player

import com.slowva.player.player.AutoIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoIdsTest {
    @Test
    fun leafRoundTripsForEveryList() {
        for (list in AutoIds.TABS + AutoIds.playlist("Road trip: 90s | rock")) {
            assertEquals(list to "yt:abc|1", AutoIds.parseLeaf(AutoIds.leaf(list, "yt:abc|1")))
        }
        assertEquals("Road trip: 90s | rock", AutoIds.playlistName(AutoIds.playlist("Road trip: 90s | rock")))
    }

    @Test
    fun foldersAndForeignIdsAreNotLeaves() {
        (AutoIds.TABS + AutoIds.ROOT + AutoIds.playlist("Mix") + "content://media/1" + "radio:uuid").forEach { assertNull(it, AutoIds.parseLeaf(it)) }
        assertNull(AutoIds.parseLeaf(AutoIds.leaf("other", "id")))
        assertNull(AutoIds.parseLeaf(AutoIds.leaf(AutoIds.SONGS, "")))
        assertNull(AutoIds.playlistName(AutoIds.SONGS))
    }

    @Test
    fun pagingStaysInBounds() {
        val items = (1..5).toList()
        assertEquals(items, AutoIds.page(items, 0, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), AutoIds.page(items, 1, Int.MAX_VALUE))
        assertEquals(listOf(3, 4), AutoIds.page(items, 1, 2))
        assertEquals(listOf(5), AutoIds.page(items, 2, 2))
        assertEquals(emptyList<Int>(), AutoIds.page(items, 3, 2))
        assertEquals(emptyList<Int>(), AutoIds.page(items, -1, 2))
        assertEquals(emptyList<Int>(), AutoIds.page(items, 0, 0))
    }
}
