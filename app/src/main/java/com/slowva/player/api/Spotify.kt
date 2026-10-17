package com.slowva.player.api

import android.content.Context
import com.slowva.player.auth.Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API client. Browsing and transport controls go through here;
 * the audio itself comes out of the local librespot device (see player/).
 */
class Spotify(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    class ApiException(val code: Int, message: String, val reason: String? = null) : Exception(message) {
        /** Spotify forgot which device plays: librespot restarted or went idle too long */
        val noActiveDevice: Boolean get() = code == 404 && (reason == "NO_ACTIVE_DEVICE" || message.orEmpty().contains("device", true))
    }

    companion object {
        /** shared across every call: one 429 pauses the whole app, not one screen */
        @Volatile
        private var rateLimitedUntil = 0L
        private const val SHORT_WAIT_SEC = 5L

        val rateLimited: Boolean get() = System.currentTimeMillis() < rateLimitedUntil
    }

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val url = "https://api.spotify.com/v1$path".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        repeat(2) { attempt ->
            waitOutRateLimit()
            val token = Auth.token(ctx) ?: throw ApiException(401, "not signed in")
            val payload: RequestBody? = when {
                body != null -> body.toRequestBody(json)
                method in setOf("PUT", "POST") -> ByteArray(0).toRequestBody(null)
                else -> null
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .method(method, payload)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return@withContext text
                    resp.code == 429 -> {
                        // Spotify says how long to stay away; everything else
                        // in the app waits with us, or we just dig deeper
                        val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: 10L
                        rateLimitedUntil = System.currentTimeMillis() + retryAfter * 1000L
                        if (attempt == 1 || retryAfter > SHORT_WAIT_SEC) {
                            throw ApiException(429, "Spotify rate limit, ${retryAfter}s to go")
                        }
                    }
                    else -> {
                        val err = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                        throw ApiException(
                            resp.code,
                            err?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(300),
                            err?.optString("reason")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
        }
        throw ApiException(429, "Spotify rate limit")
    }

    private suspend fun waitOutRateLimit() {
        val wait = rateLimitedUntil - System.currentTimeMillis()
        if (wait <= 0) return
        if (wait > SHORT_WAIT_SEC * 1000) {
            throw ApiException(429, "Spotify rate limit, ${wait / 1000}s to go")
        }
        delay(wait)
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()) =
        JSONObject(request("GET", path, query).ifBlank { "{}" })

    // ---- playback -------------------------------------------------------

    suspend fun devices(): List<Device> =
        get("/me/player/devices").optJSONArray("devices").map { it.toDevice() }

    suspend fun transfer(deviceId: String, play: Boolean) {
        request(
            "PUT", "/me/player",
            body = JSONObject()
                .put("device_ids", JSONArray().put(deviceId))
                .put("play", play).toString()
        )
    }

    suspend fun play(
        deviceId: String?,
        contextUri: String? = null,
        uris: List<String>? = null,
        offset: Int? = null,
        positionMs: Int = 0,
    ) {
        val body = JSONObject()
        if (contextUri != null) body.put("context_uri", contextUri)
        if (uris != null) body.put("uris", JSONArray(uris))
        if (offset != null) body.put("offset", JSONObject().put("position", offset))
        body.put("position_ms", positionMs)
        request(
            "PUT", "/me/player/play",
            query = deviceId?.let { mapOf("device_id" to it) } ?: emptyMap(),
            body = body.toString()
        )
    }

    suspend fun resume(deviceId: String?) = request(
        "PUT", "/me/player/play",
        query = deviceId?.let { mapOf("device_id" to it) } ?: emptyMap(),
        body = "{}"
    ).let { }

    suspend fun pause() = request("PUT", "/me/player/pause").let { }
    suspend fun next() = request("POST", "/me/player/next").let { }
    suspend fun previous() = request("POST", "/me/player/previous").let { }
    suspend fun seek(ms: Int) =
        request("PUT", "/me/player/seek", mapOf("position_ms" to ms.toString())).let { }

    suspend fun setVolume(percent: Int) =
        request("PUT", "/me/player/volume", mapOf("volume_percent" to percent.toString())).let { }

    suspend fun setShuffle(on: Boolean) =
        request("PUT", "/me/player/shuffle", mapOf("state" to on.toString())).let { }

    suspend fun setRepeat(mode: String) =
        request("PUT", "/me/player/repeat", mapOf("state" to mode)).let { }

    suspend fun state(): PlaybackState? {
        val raw = request("GET", "/me/player", mapOf("additional_types" to "track"))
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val item = o.optJSONObject("item")
        val device = o.optJSONObject("device")
        return PlaybackState(
            track = item?.toTrack(),
            isPlaying = o.optBoolean("is_playing"),
            progressMs = o.optInt("progress_ms"),
            deviceId = device?.optString("id"),
            deviceName = device?.optString("name"),
            shuffle = o.optBoolean("shuffle_state"),
            repeat = o.optString("repeat_state", "off"),
            volumePercent = device?.optInt("volume_percent", 100) ?: 100,
        )
    }

    // ---- library --------------------------------------------------------

    suspend fun likedTracks(max: Int = 200): List<Track> =
        pages("/me/tracks", max) { it.optJSONObject("track")?.toTrack() }

    suspend fun playlists(max: Int = 50): List<Playlist> =
        pages("/me/playlists", max) { it.toPlaylist() }

    suspend fun albums(max: Int = 50): List<Album> =
        pages("/me/albums", max) { it.optJSONObject("album")?.toAlbum() }

    suspend fun playlistTracks(id: String, max: Int = 1000): List<Track> =
        pages("/playlists/$id/items", max) {
            // Spotify's current response is `item`; keep `track` as a fallback
            // for accounts still served by the older playlist schema.
            (it.optJSONObject("item") ?: it.optJSONObject("track"))?.toTrack()
        }

    suspend fun albumTracks(album: Album, max: Int = 200): List<Track> =
        pages("/albums/${album.id}/tracks", max) {
            it.toTrack(albumName = album.name, art = album.art)
        }

    suspend fun search(query: String): Triple<List<Track>, List<Album>, List<Playlist>> {
        // Separate calls mirror spotc and avoid the gateway inconsistencies of
        // comma-separated search types. Spotify currently caps each type at 10.
        val tracks = get(
            "/search", mapOf("q" to query, "type" to "track", "limit" to "10")
        )
        val albums = get(
            "/search", mapOf("q" to query, "type" to "album", "limit" to "10")
        )
        val playlists = get(
            "/search", mapOf("q" to query, "type" to "playlist", "limit" to "10")
        )
        return Triple(
            tracks.optJSONObject("tracks")?.optJSONArray("items").map { it.toTrack() },
            albums.optJSONObject("albums")?.optJSONArray("items").map { it.toAlbum() },
            playlists.optJSONObject("playlists")?.optJSONArray("items").map { it.toPlaylist() },
        )
    }

    /** Walks a paginated endpoint 50 items at a time, like spotc does. */
    private suspend fun <T> pages(path: String, max: Int, parse: (JSONObject) -> T?): List<T> {
        val out = mutableListOf<T>()
        var offset = 0
        var firstPageEmptyRetries = 0
        while (out.size < max) {
            val page = get(path, mapOf("limit" to "50", "offset" to offset.toString()))
            val items = page.optJSONArray("items") ?: break
            if (items.length() == 0) {
                // Spotify occasionally returns items=[] together with a real
                // non-zero total. spotc retries this transient response rather
                // than replacing a populated list with an empty one.
                if (offset == 0 && page.optInt("total", 0) > 0 && firstPageEmptyRetries < 2) {
                    firstPageEmptyRetries++
                    delay(300)
                    continue
                }
                if (offset == 0 && page.optInt("total", 0) > 0) {
                    throw ApiException(502, "Spotify returned an empty listing — try again")
                }
                break
            }
            firstPageEmptyRetries = 0
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                parse(obj)?.let { out.add(it) }
            }
            if (page.isNull("next")) break
            offset += 50
        }
        return out
    }
}

// ---- JSON helpers -------------------------------------------------------

private inline fun <T> JSONArray?.map(parse: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    val out = mutableListOf<T>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        parse(obj)?.let { out.add(it) }
    }
    return out
}

private fun JSONObject.artists(): String {
    val arr = optJSONArray("artists") ?: return ""
    return (0 until arr.length())
        .mapNotNull { arr.optJSONObject(it)?.optString("name") }
        .joinToString(", ")
}

private fun JSONObject.image(): String? {
    val arr = optJSONArray("images") ?: return null
    return arr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
}

internal fun JSONObject.toTrack(albumName: String? = null, art: String? = null): Track? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    val album = optJSONObject("album")
    return Track(
        id = id,
        uri = optString("uri"),
        name = optString("name"),
        artist = artists(),
        album = albumName ?: album?.optString("name").orEmpty(),
        durationMs = optInt("duration_ms"),
        art = art ?: album?.image(),
    )
}

internal fun JSONObject.toAlbum(): Album? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    return Album(id, optString("uri"), optString("name"), artists(), image())
}

internal fun JSONObject.toPlaylist(): Playlist? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    return Playlist(
        id, optString("uri"), optString("name"),
        optJSONObject("owner")?.optString("display_name").orEmpty(), image()
    )
}

internal fun JSONObject.toDevice(): Device? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    return Device(
        id, optString("name"), optString("type"),
        optBoolean("is_active"), optInt("volume_percent", 100)
    )
}
