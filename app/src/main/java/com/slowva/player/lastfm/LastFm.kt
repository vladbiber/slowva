package com.slowva.player.lastfm

import android.content.Context
import com.slowva.player.BuildConfig
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkBlockedException
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Tags, artists and top songs from Last.fm. Answers are kept a day; without an API key [enabled] is false. */
object LastFm {
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    // Last.fm serves this grey star for every artist photo it no longer has
    private const val PLACEHOLDER = "2a96cbd8b46e442fc41c2b86b821562f"
    private val NOT_GENRES = setOf("seen live", "female vocalists", "male vocalists", "favorites", "favourites", "beautiful", "awesome", "love", "british", "american", "under 2000 listeners")

    data class Artist(val name: String, val image: String?)
    data class Song(val title: String, val artist: String)

    val enabled: Boolean get() = BuildConfig.LASTFM_API_KEY.isNotBlank()

    private var dir: File? = null
    private val memory = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "lastfm").apply { mkdirs() }
    }

    /** Popular genre tags, most used first; tags that aren't genres are left out. */
    suspend fun topTags(): List<String> =
        list(call("chart.getTopTags", "limit" to "60"), "tags", "tag").map { it.getString("name").lowercase() }.filter { it !in NOT_GENRES }

    suspend fun tagArtists(tag: String): List<Artist> = artists(list(call("tag.getTopArtists", "tag" to tag, "limit" to "30"), "topartists", "artist"))

    suspend fun tagTracks(tag: String): List<Song> = songs(list(call("tag.getTopTracks", "tag" to tag, "limit" to "50"), "tracks", "track"))

    /** Album covers for a genre card; album art is the one image Last.fm still serves reliably. */
    suspend fun tagCovers(tag: String): List<String> =
        list(call("tag.getTopAlbums", "tag" to tag, "limit" to "8"), "albums", "album").mapNotNull { image(it) }

    suspend fun similar(artist: String): List<Artist> =
        artists(list(call("artist.getSimilar", "artist" to artist, "limit" to "20", "autocorrect" to "1"), "similarartists", "artist"))

    suspend fun artistTracks(artist: String): List<Song> =
        songs(list(call("artist.getTopTracks", "artist" to artist, "limit" to "30", "autocorrect" to "1"), "toptracks", "track"))

    /** The artist's most used tag that is a genre, for "Go to genre". */
    suspend fun artistTopTag(artist: String): String? =
        list(call("artist.getTopTags", "artist" to artist, "autocorrect" to "1"), "toptags", "tag").map { it.getString("name").lowercase() }.firstOrNull { it !in NOT_GENRES }

    /** Artists most played worldwide, to tell a region's own artists from global ones. */
    suspend fun globalArtists(): List<Artist> = artists(list(call("chart.getTopArtists", "limit" to "100"), "artists", "artist"))

    /** A country's most played artists; [country] is its ISO 3166 English name, like "Nigeria". */
    suspend fun countryArtists(country: String): List<Artist> = artists(list(call("geo.getTopArtists", "country" to country, "limit" to "50"), "topartists", "artist"))

    /** A country's chart with each song's listener count. */
    suspend fun countryTracks(country: String): List<Pair<Song, Long>> =
        list(call("geo.getTopTracks", "country" to country, "limit" to "100"), "tracks", "track").mapNotNull { t -> songs(listOf(t)).firstOrNull()?.let { it to t.optLong("listeners") } }

    private fun artists(a: List<JSONObject>) = a.map { Artist(it.getString("name"), image(it)) }

    private fun songs(a: List<JSONObject>) = a.mapNotNull { t ->
        val artist = t.optJSONObject("artist")?.optString("name").orEmpty()
        Song(t.getString("name"), artist).takeIf { artist.isNotBlank() }
    }

    /** The largest real picture, or null for the placeholder. */
    private fun image(o: JSONObject): String? {
        val imgs = o.optJSONArray("image") ?: return null
        return (imgs.length() - 1 downTo 0).firstNotNullOfOrNull { i -> imgs.optJSONObject(i)?.optString("#text")?.takeIf { it.isNotBlank() } }
            ?.takeUnless { PLACEHOLDER in it }
    }

    /** Last.fm sends a lone item as an object instead of a one-item array. */
    private fun list(o: JSONObject, outer: String, inner: String): List<JSONObject> {
        val box = o.optJSONObject(outer) ?: return emptyList()
        box.optJSONArray(inner)?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        return listOfNotNull(box.optJSONObject(inner))
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        if (!enabled) throw IOException("No Last.fm key in this build")
        val key = method + params.joinToString("") { "&${it.first}=${it.second.lowercase()}" }
        val now = System.currentTimeMillis()
        memory[key]?.let { (at, o) -> if (now - at < TTL_MS) return@withContext o }
        val file = dir?.let { File(it, key.filter(Char::isLetterOrDigit).take(80) + "_" + key.hashCode().toUInt() + ".json") }
        if (file != null && file.exists() && now - file.lastModified() < TTL_MS) {
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { memory[key] = file.lastModified() to it; return@withContext it }
        }
        try {
            NetworkGate.blockReason(Feature.LASTFM)?.let { throw NetworkBlockedException(Feature.LASTFM, it) }
            val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                .addQueryParameter("method", method)
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .addQueryParameter("api_key", BuildConfig.LASTFM_API_KEY)
                .addQueryParameter("format", "json")
                .build()
            val body = NetworkGate.client.newCall(NetworkGate.request(url.toString(), Feature.LASTFM).build()).execute().use { r ->
                // error bodies are JSON too, with the reason in "message"
                r.body.string()
            }
            val o = runCatching { JSONObject(body) }.getOrElse { throw IOException("Last.fm sent something unreadable") }
            if (o.has("error")) throw IOException("Last.fm: ${o.optString("message")}")
            memory[key] = now to o
            file?.let { f -> runCatching { f.writeText(body) } }
            o
        } catch (e: IOException) {
            // an old answer beats none
            file?.takeIf { it.exists() }?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull() } ?: throw e
        }
    }
}
