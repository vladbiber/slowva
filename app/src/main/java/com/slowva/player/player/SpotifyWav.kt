package com.slowva.player.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The WAV framing around the decoder's raw s16le stereo 44.1 kHz PCM. */
object SpotifyWav {
    const val RATE = 44100
    const val FRAME = 4
    const val HEADER = 44L

    fun dataBytes(durationMs: Long): Long = durationMs.coerceAtLeast(0L) * RATE / 1000 * FRAME

    fun header(dataBytes: Long): ByteArray {
        val size = dataBytes.coerceIn(0L, 0xFFFFFFFFL - 36)
        val b = ByteBuffer.allocate(HEADER.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + size).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2)
        b.putInt(RATE); b.putInt(RATE * FRAME); b.putShort(FRAME.toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(size.toInt())
        return b.array()
    }

    /** Where to start the decoder for a read at byte [position], and how many PCM bytes to drop so the stream lines up. */
    data class Start(val headerSkip: Int, val startMs: Long, val skipBytes: Long)

    fun start(position: Long): Start {
        if (position < HEADER) return Start(position.toInt().coerceAtLeast(0), 0L, 0L)
        val data = position - HEADER
        val startMs = data / FRAME * 1000 / RATE
        val startFrame = startMs * RATE / 1000
        return Start(HEADER.toInt(), startMs, data - startFrame * FRAME)
    }
}
