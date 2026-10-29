package com.slowva.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.slowva.player.diag.Diagnostics
import com.slowva.player.diag.Failure
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkBlockedException
import com.slowva.player.net.NetworkGate
import com.slowva.player.net.Traffic
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyIds
import com.slowva.player.spotify.SpotifyNative
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** A song Spotify won't play at all; FileNotFound so ExoPlayer doesn't retry it. */
class SpotifyUnavailableException(message: String) : FileNotFoundException(message)

/**
 * Plays `spstream://<id>?ms=<duration>` as a WAV: a header, then the decoder's PCM.
 * WavExtractor seeks by reopening at a byte position; the decoder starts at that millisecond.
 */
@UnstableApi
class SpotifyDataSource(private val ctx: Context) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var process: Process? = null
    private var input: InputStream? = null
    private var header = ByteArray(0)
    private var headerPos = 0
    private var pcmLeft = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = SpotifyIds.spotifyId(dataSpec.uri.toString()) ?: throw SpotifyUnavailableException("Not a Spotify song")
        NetworkGate.blockReason(Feature.STREAMING, Traffic.STREAM)?.let { throw NetworkBlockedException(Feature.STREAMING, it) }
        if (!SpotifyNative.hasCredentials(ctx)) throw fail(id, SpotifyUnavailableException("Not signed in to Spotify"))
        val ms = dataSpec.uri.getQueryParameter("ms")?.toLongOrNull() ?: 0L
        // without a duration the header claims the most a WAV can hold, and the stream simply ends
        val data = if (ms > 0) SpotifyWav.dataBytes(ms) else 0xFFFFFFFFL - 36
        val start = SpotifyWav.start(dataSpec.position)
        header = SpotifyWav.header(data)
        headerPos = start.headerSkip
        pcmLeft = data - (dataSpec.position - SpotifyWav.HEADER).coerceAtLeast(0L)
        if (pcmLeft > 0) {
            val p = SpotifyNative.start(
                ctx, SpotifyNative.DECODE,
                listOf(SpotifyNative.cacheDir(ctx).absolutePath, "spotify:track:$id", "-", Spotify.bitrate.value.toString(), start.startMs.toString()),
            )
            process = p
            input = p.inputStream
            skip(id, start.skipBytes)
        }
        opened = true
        transferStarted(dataSpec)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headerPos < header.size) {
            val n = minOf(length, header.size - headerPos)
            System.arraycopy(header, headerPos, buffer, offset, n)
            headerPos += n
            bytesTransferred(n)
            return n
        }
        if (pcmLeft <= 0) return C.RESULT_END_OF_INPUT
        val n = pcm(buffer, offset, minOf(length.toLong(), pcmLeft).toInt())
        if (n < 0) {
            ended()
            return C.RESULT_END_OF_INPUT
        }
        pcmLeft -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        input?.let { runCatching { it.close() } }
        input = null
        process?.destroy()
        process = null
        if (opened) { opened = false; transferEnded() }
    }

    private fun skip(id: String, bytes: Long) {
        val scratch = ByteArray(16 * 1024)
        var left = bytes
        while (left > 0) {
            val n = pcm(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) { ended(id); pcmLeft = 0; return }
            left -= n
        }
    }

    /** Reads PCM, polling so a cancelled load (seek, skip) doesn't sit in a blocking pipe read. */
    private fun pcm(buffer: ByteArray, offset: Int, length: Int): Int {
        val inp = input ?: return -1
        val p = process ?: return -1
        try {
            while (inp.available() == 0 && p.isAlive) {
                if (Thread.interrupted()) throw InterruptedIOException()
                Thread.sleep(3)
            }
        } catch (e: InterruptedException) {
            throw InterruptedIOException()
        }
        return inp.read(buffer, offset, length)
    }

    /** The decoder closed its output: a clean end, or a failure to report. */
    private fun ended(id: String? = null) {
        val p = process ?: return
        if (!p.waitFor(2, TimeUnit.SECONDS)) return
        val code = p.exitValue()
        if (code == 0) return
        val song = id ?: SpotifyIds.spotifyId(uri.toString()).orEmpty()
        val msg = SpotifyNative.decodeError(code)
        SpotifyNative.note(ctx, "decode $song exit $code: $msg")
        // dropped connections are retried from where they stopped; the rest can't play
        throw fail(song, if (code == 5 || code == 7) IOException(msg) else SpotifyUnavailableException(msg))
    }

    private fun fail(id: String, e: IOException): IOException {
        Diagnostics.record(Failure("spotify", "play", "libslowvadecode", item = id, error = e, errorClass = if (e is SpotifyUnavailableException) "unavailable" else "spotify_connect"))
        return e
    }
}
