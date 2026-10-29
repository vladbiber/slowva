package com.slowva.player.data

import com.slowva.player.data.db.RatingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RymTest {
    private val header = "RYM Album, First Name,Last Name,First Name localized, Last Name localized,Title,Release_Date,Rating,Ownership,Purchase Date,Media Type,Review"

    @Test
    fun csvHandlesQuotesCommasNewlinesAndBom() {
        val rows = Rym.parseCsv("﻿a,\"b, c\",\"say \"\"hi\"\"\"\r\n\"multi\nline\",,x\n")
        assertEquals(listOf(listOf("a", "b, c", "say \"hi\""), listOf("multi\nline", "", "x")), rows)
    }

    @Test
    fun csvKeepsTrailingEmptyFieldAndSkipsBlankLines() {
        assertEquals(listOf(listOf("a", ""), listOf("b", "c")), Rym.parseCsv("a,\n\nb,c"))
    }

    @Test
    fun rowsFindColumnsByNameAndJoinArtist() {
        val csv = "$header,Extra\n" +
            "1,Thom,Yorke,,,The Eraser,2006,8,o,,CD,\"Great, \"\"quiet\"\" record\",zzz\n" +
            "2,,Radiohead,,,OK Computer,06/16/1997,10,w,,,\n" +
            "3,,Somebody,,,Unrated,2001,0,u,,,\n"
        val rows = Rym.rows(csv)
        assertEquals(3, rows.size)
        assertEquals("Thom Yorke", rows[0].artist)
        assertEquals("Great, \"quiet\" record", rows[0].review)
        assertEquals("Radiohead", rows[1].artist)
        assertEquals(1997, rows[1].year)
        assertEquals(10, rows[1].rating)
        assertNull(rows[1].review)
    }

    @Test
    fun reorderedColumnsStillWork() {
        val r = Rym.rows("Rating,Title,Last Name,First Name\n7,Blue,Mitchell,Joni\n").single()
        assertEquals("Joni Mitchell", r.artist)
        assertEquals(7, r.rating)
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherCsvIsRefused() {
        Rym.rows("name,value\na,b\n")
    }

    @Test
    fun mappingSkipsUnratedAndBadLinesAndKeysAlbums() {
        val rows = listOf(
            RymRow("Radiohead", "OK Computer", 1997, 10, "o", null),
            RymRow("Radiohead", "Kid A", 2000, 0, "o", null),
            RymRow("Radiohead", "Amnesiac", 2001, 0, "n", "Odd one"),
            RymRow("", "No Artist", null, 6, "", null),
            RymRow("Björk", "Homogenic (Deluxe Edition)", 1997, 9, "", null),
            RymRow("Someone", "Too High", null, 11, "", null),
        )
        val out = Rym.toRatings(rows, 1L)
        assertEquals(3, out.size)
        assertTrue(out.all { it.kind == RatingKind.ALBUM })
        assertEquals("radiohead|ok computer", out[0].key)
        assertEquals(1997, out[0].year)
        assertEquals(0, out[1].value)
        assertEquals("Odd one", out[1].review)
        // accents, edition suffixes and case don't matter
        assertEquals(RatingKeys.album("bjork", "Homogenic"), out[2].key)
        assertEquals(setOf("radiohead", "bjork"), Rym.likedArtists(out))
    }

    @Test
    fun keysAndLabels() {
        assertEquals("abc", RatingKeys.song("abc"))
        assertEquals(RatingKeys.artist("AC/DC"), RatingKeys.artist("ac dc"))
        assertEquals("beyonce", RatingKeys.artist("Beyoncé feat. Jay-Z"))
        assertNull(RatingKeys.album("x", "Imported files"))
        assertEquals("3.5 stars", starsLabel(7))
        assertEquals("1 star", starsLabel(2))
        assertEquals("5 stars", starsLabel(10))
    }
}
