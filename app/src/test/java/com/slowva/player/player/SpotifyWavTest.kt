package com.slowva.player.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpotifyWavTest {
    @Test
    fun dataSizeIsWholeFramesOfTheDuration() {
        assertEquals(44100L * 4, SpotifyWav.dataBytes(1000))
        assertEquals(4410L * 4, SpotifyWav.dataBytes(100))
        // 1 ms is 44.1 frames: the fraction is dropped, the size stays frame aligned
        assertEquals(44L * 4, SpotifyWav.dataBytes(1))
        assertEquals(0L, SpotifyWav.dataBytes(-5))
    }

    @Test
    fun headerDescribesStereo16Bit44k() {
        val h = SpotifyWav.header(1000)
        assertEquals(44, h.size)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals("RIFF".toByteArray(), h.copyOfRange(0, 4))
        assertEquals(1036, b.getInt(4))
        assertArrayEquals("WAVEfmt ".toByteArray(), h.copyOfRange(8, 16))
        assertEquals(16, b.getInt(16))
        assertEquals(1.toShort(), b.getShort(20))
        assertEquals(2.toShort(), b.getShort(22))
        assertEquals(44100, b.getInt(24))
        assertEquals(176400, b.getInt(28))
        assertEquals(4.toShort(), b.getShort(32))
        assertEquals(16.toShort(), b.getShort(34))
        assertArrayEquals("data".toByteArray(), h.copyOfRange(36, 40))
        assertEquals(1000, b.getInt(40))
    }

    @Test
    fun readsInsideTheHeaderStartTheDecoderAtZero() {
        assertEquals(SpotifyWav.Start(0, 0, 0), SpotifyWav.start(0))
        assertEquals(SpotifyWav.Start(20, 0, 0), SpotifyWav.start(20))
        assertEquals(SpotifyWav.Start(44, 0, 0), SpotifyWav.start(44))
    }

    @Test
    fun seekStartsAtTheMillisecondAndSkipsTheRest() {
        // exactly 1 s in: frame 44100 is a whole millisecond, nothing to skip
        assertEquals(SpotifyWav.Start(44, 1000, 0), SpotifyWav.start(44 + 44100L * 4))
        // frame 100 lies inside ms 2 (frames 88..132): start there, drop 12 frames
        assertEquals(SpotifyWav.Start(44, 2, 12L * 4), SpotifyWav.start(44 + 100L * 4))
        // an odd byte offset is kept exactly, not rounded to a frame
        assertEquals(SpotifyWav.Start(44, 2, 12L * 4 + 2), SpotifyWav.start(44 + 100L * 4 + 2))
    }

    @Test
    fun seekLinesUpForEveryOffset() {
        for (frame in listOf(1L, 43L, 44L, 45L, 441L, 12_345L, 7_938_000L, 13_230_001L)) {
            for (extra in 0..3) {
                val pos = 44 + frame * 4 + extra
                val s = SpotifyWav.start(pos)
                val decoderFrame = s.startMs * 44100 / 1000
                assertEquals(pos - 44, decoderFrame * 4 + s.skipBytes)
                // never more than one millisecond of audio thrown away
                assert(s.skipBytes in 0 until 45 * 4) { "skip ${s.skipBytes} at $pos" }
            }
        }
    }
}
