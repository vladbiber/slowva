package com.slowva.player.spotify

import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException

class SpotifyApiException(val code: Int, message: String) : IOException(message)

/** Spotify Web API reads, through the network gate as "Online songs". */
object SpotifyApi {
    /** one 429 pauses every call, not just one screen */
    @Volatile private var limitedUntil = 0L
    private const val SHORT_WAIT_MS = 5_000L
    private const val PAGE = 50

    val rateLimited get() = System.currentTimeMillis() < limitedUntil

    suspend fun search(q: String, limit: Int = 10): SpResults =
        SpotifyParse.search(get("/search", mapOf("q" to q, "type" to "track,album,playlist,artist", "limit" to "$limit")))

    suspend fun searchTracks(q: String, limit: Int = 10): List<SpTrack> =
        SpotifyParse.search(get("/search", mapOf("q" to q, "type" to "track", "limit" to "$limit"))).tracks

    suspend fun liked(max: Int = 500) = pages("/me/tracks", max, SpotifyParse::savedTrack)
    suspend fun playlists(max: Int = 200) = pages("/me/playlists", max, SpotifyParse::playlist)
    suspend fun albums(max: Int = 200) = pages("/me/albums", max, SpotifyParse::savedAlbum)
    suspend fun playlistTracks(id: String, max: Int = 1000) = pages("/playlists/$id/items", max, SpotifyParse::playlistItem)

    suspend fun albumTracks(album: SpAlbum, max: Int = 300): List<SpTrack> =
        pages("/albums/${album.id}/tracks", max) { SpotifyParse.track(it, album.name, album.art) }

    suspend fun artistTopTracks(artistId: String): List<SpTrack> =
        SpotifyParse.list(get("/artists/$artistId/top-tracks", mapOf("market" to "from_token")).optJSONArray("tracks")) { SpotifyParse.track(it) }

    /** Walks a paginated listing [PAGE] items at a time. */
    private suspend fun <T> pages(path: String, max: Int, parse: (JSONObject) -> T?): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        var emptyRetries = 0
        while (out.size < max) {
            val page = get(path, mapOf("limit" to "$PAGE", "offset" to "$offset"))
            val items = page.optJSONArray("items") ?: break
            if (items.length() == 0) {
                // Spotify sometimes answers items=[] with a real total; asking again fixes it
                if (offset == 0 && page.optInt("total") > 0 && emptyRetries++ < 2) { delay(300); continue }
                break
            }
            out += SpotifyParse.list(items, parse)
            if (page.isNull("next")) break
            offset += PAGE
        }
        return out.take(max)
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://api.spotify.com/v1$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        // the session's quota is shared with every librespot app, so a signed-in personal Client ID goes first
        var personal = Spotify.personalSignedIn.value
        repeat(3) { attempt ->
            waitOutLimit()
            val token = Spotify.webToken(personal) ?: throw SpotifyApiException(401, Spotify.lastError.value ?: "Not signed in to Spotify")
            val req = NetworkGate.request(url.toString(), Feature.STREAMING).header("Authorization", "Bearer $token").build()
            NetworkGate.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return@withContext JSONObject(text.ifBlank { "{}" })
                    resp.code == 401 -> Spotify.forget(token)
                    resp.code == 429 -> {
                        val wait = (resp.header("Retry-After")?.toLongOrNull() ?: 10L) * 1000
                        // the session's quota is spent: try the personal Client ID
                        if (!personal && Spotify.personalSignedIn.value) { personal = true; return@use }
                        limitedUntil = System.currentTimeMillis() + wait
                        if (wait > SHORT_WAIT_MS || attempt == 2) throw SpotifyApiException(429, "Spotify is rate limiting, try again in ${wait / 1000} s")
                    }
                    else -> {
                        val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                        throw SpotifyApiException(resp.code, "Spotify replied ${resp.code}" + (msg?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                    }
                }
            }
        }
        throw SpotifyApiException(429, "Spotify is rate limiting")
    }

    private suspend fun waitOutLimit() {
        val wait = limitedUntil - System.currentTimeMillis()
        if (wait <= 0) return
        if (wait > SHORT_WAIT_MS) throw SpotifyApiException(429, "Spotify is rate limiting, try again in ${wait / 1000} s")
        delay(wait)
    }
}
