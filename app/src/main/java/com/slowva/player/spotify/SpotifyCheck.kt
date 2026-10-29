package com.slowva.player.spotify

import android.content.Context
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import com.slowva.player.net.Traffic
import com.slowva.player.player.SpotifyWav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The diagnostics test: every link from the network switch to one second of decoded audio. */
object SpotifyCheck {
    private const val PROBE = "4uLU6hMCjMI75M1A2tKUQC"

    suspend fun run(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        out += "Network: " + (NetworkGate.blockReason(Feature.STREAMING, Traffic.STREAM) ?: "allowed")
        val dir = ctx.applicationInfo.nativeLibraryDir
        out += "Helpers: " + listOf(SpotifyNative.DECODE, SpotifyNative.TOKEN).joinToString { if (File(dir, it).isFile) "$it ok" else "$it MISSING" }
        if (!SpotifyNative.hasCredentials(ctx)) return@withContext out + "Session: not signed in"
        out += "Session: credentials saved"
        var t = System.currentTimeMillis()
        val token = Spotify.webToken()
        out += if (token != null) "Web API token: ok in ${System.currentTimeMillis() - t} ms" else "Web API token: failed, ${Spotify.lastError.value}"
        var probe = PROBE
        t = System.currentTimeMillis()
        out += try {
            val hits = SpotifyApi.searchTracks("Never Gonna Give You Up", 1)
            hits.firstOrNull()?.let { probe = it.id }
            "Search: ${hits.size} result in ${System.currentTimeMillis() - t} ms"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Search: ${e.message}"
        }
        out += decodeSecond(ctx, probe)
        out += "Tokens: " + Spotify.tokenSummary()
        out
    }

    private fun decodeSecond(ctx: Context, id: String): String {
        val t = System.currentTimeMillis()
        val p = runCatching {
            SpotifyNative.start(ctx, SpotifyNative.DECODE, listOf(SpotifyNative.cacheDir(ctx).absolutePath, "spotify:track:$id", "-", Spotify.bitrate.value.toString()))
        }.getOrElse { return "Decoder: ${it.message}" }
        try {
            val want = SpotifyWav.RATE * SpotifyWav.FRAME
            val buf = ByteArray(16 * 1024)
            var got = 0
            p.inputStream.use { inp ->
                while (got < want) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    got += n
                }
            }
            if (got >= want) return "Decoder: 1 s of audio in ${System.currentTimeMillis() - t} ms"
            p.waitFor()
            return "Decoder: " + SpotifyNative.decodeError(p.exitValue())
        } finally {
            p.destroy()
        }
    }
}
