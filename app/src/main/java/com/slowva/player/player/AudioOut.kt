package com.slowva.player.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Stereo s16le AudioTrack that rebuilds itself when Android kills it.
 *
 * A Bluetooth switch, a call or an audioserver restart can leave the track
 * dead: every write returns an error and nothing plays again until a new
 * track is made.
 */
class AudioOut(private val rate: Int) {

    private var track = build()
    private var wantPlaying = false
    private var volume = 1f

    val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING

    fun play() { wantPlaying = true; runCatching { track.play() } }
    fun pause() { wantPlaying = false; runCatching { track.pause() } }
    fun flush() { runCatching { track.flush() } }

    fun setVolume(v: Float) {
        if (v == volume) return
        volume = v
        runCatching { track.setVolume(v) }
    }

    /** Writes [len] bytes, blocking, while [keepGoing] holds. */
    fun write(bytes: ByteArray, len: Int, keepGoing: () -> Boolean) {
        var written = 0
        var failures = 0
        while (written < len && keepGoing()) {
            val n = track.write(bytes, written, len - written)
            if (n > 0) { written += n; failures = 0; continue }
            if (n == 0) return
            Log.w(TAG, "audio track error $n, rebuilding")
            rebuild()
            if (++failures >= 3) {
                // the output is gone for now; drop this chunk instead of spinning
                Thread.sleep(200)
                return
            }
        }
    }

    private fun rebuild() {
        runCatching { track.release() }
        track = build()
        runCatching { track.setVolume(volume) }
        if (wantPlaying) runCatching { track.play() }
    }

    fun release() {
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun build(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, 64 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "slowva-out"
    }
}
