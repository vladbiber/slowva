package com.slowva.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTest {
    @Test
    fun parsesStampsTagsAndRepeats() {
        val lines = Lrc.parse(
            """
            [ar:Someone]
            [00:12.34] First line
            [01:02.5][00:20.000]Chorus
            [00:30]
            not a lyric
            """.trimIndent()
        )
        assertEquals(
            listOf(
                LyricLine(12_340, "First line"),
                LyricLine(20_000, "Chorus"),
                LyricLine(30_000, ""),
                LyricLine(62_500, "Chorus"),
            ),
            lines,
        )
        assertEquals(-1, Lrc.indexAt(lines, 5_000))
        assertEquals(0, Lrc.indexAt(lines, 12_340))
        assertEquals(2, Lrc.indexAt(lines, 45_000))
        assertEquals(3, Lrc.indexAt(lines, 999_000))
    }
}
