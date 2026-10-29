package com.slowva.player.musicbrainz

import android.content.Context
import com.slowva.player.BuildConfig
import com.slowva.player.data.RatingKeys
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkBlockedException
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Album facts from MusicBrainz, one request a second, answers kept on disk for a month. */
object MusicBrainz {
    private const val BASE = "https://musicbrainz.org/ws/2/"
    private const val SPACING_MS = 1_100L
    private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MISSING_TTL_MS = 7L * 24 * 60 * 60 * 1000

    val userAgent = "slowva/${BuildConfig.VERSION_NAME} ( https://github.com/vladbiber/slowva )"

    private val gate = Mutex()
    private var lastAt = 0L
    private var dir: File? = null
    private val memory = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.cacheDir, "musicbrainz").apply { mkdirs() }
    }

    fun coverUrl(groupId: String) = "https://coverartarchive.org/release-group/$groupId/front-250"

    /** [album] by [artist], or null when MusicBrainz doesn't know it. Throws when offline with nothing cached. */
    suspend fun album(artist: String, album: String): MbAlbum? {
        val key = "album_" + (RatingKeys.album(artist, album) ?: (RatingKeys.artist(artist) + "|" + MbParse.titleKey(album)))
        return cached(key) {
            val hits = get("release-group", "query" to "releasegroup:${quote(stripEdition(album))} AND artist:${quote(artist)}", "limit" to "15")
            val g = hits?.let { MbParse.best(MbParse.groups(it), artist, album) } ?: return@cached null
            val full = get("release-group/${g.id}", "inc" to "ratings+genres+tags+releases") ?: return@cached null
            val (rating, votes) = MbParse.rating(full)
            val tracks = MbParse.pickRelease(full)?.let { id -> get("release/$id", "inc" to "recordings") }?.let(MbParse::tracks).orEmpty()
            MbAlbum(g, rating, votes, MbParse.tags(full), tracks).toJson()
        }?.let(MbAlbum::fromJson)
    }

    /** Studio albums then EPs of [artist], oldest first; empty when the artist isn't found. */
    suspend fun discography(artist: String): List<MbGroup> {
        val id = artistId(artist) ?: return emptyList()
        val o = cached("disco_" + RatingKeys.artist(artist)) {
            // a search, not a browse: browsing lists hundreds of bootlegs before the studio albums
            get("release-group", "query" to "arid:$id AND (primarytype:album OR primarytype:ep) AND NOT secondarytype:*", "limit" to "100")
                ?.let { JSONObject().put("groups", JSONArray(MbParse.discography(it).map(::groupJson))) }
        } ?: return emptyList()
        return MbParse.albumsFirst(objects(o.optJSONArray("groups")).mapNotNull(MbParse::group))
    }

    private suspend fun artistId(name: String): String? =
        cached("artist_" + RatingKeys.artist(name)) {
            get("artist", "query" to "artist:${quote(name)}", "limit" to "5")?.let { MbParse.artistId(it, name) }?.let { JSONObject().put("id", it) }
        }?.optString("id")?.takeIf { it.isNotEmpty() }

    private fun groupJson(g: MbGroup) = JSONObject().put("id", g.id).put("title", g.title)
        .put("artist-credit", JSONArray().put(JSONObject().put("name", g.artist)))
        .put("first-release-date", g.year?.toString() ?: "").put("primary-type", g.primaryType ?: JSONObject.NULL)
        .put("secondary-types", JSONArray(g.secondaryTypes))

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun stripEdition(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*(deluxe|remaster|edition|expanded|anniversary|bonus)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "").trim().ifEmpty { s }

    /** Disk and memory cache; a null from [load] is kept as "not found" for a week. Stale answers beat failures. */
    private suspend fun cached(key: String, load: suspend () -> JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val file = dir?.let { File(it, key.filter(Char::isLetterOrDigit).take(80) + "_" + key.hashCode().toUInt() + ".json") }
        val entry = memory[key] ?: file?.takeIf { it.exists() }?.let { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }?.let { o -> o.optLong("at") to o }
        if (entry != null) {
            val (at, o) = entry
            val found = o.has("data")
            if (now - at < if (found) FOUND_TTL_MS else MISSING_TTL_MS) { memory[key] = entry; return@withContext o.optJSONObject("data") }
        }
        val data = try {
            load()
        } catch (e: IOException) {
            if (entry != null) return@withContext entry.second.optJSONObject("data")
            throw e
        }
        val box = JSONObject().put("at", now).apply { if (data != null) put("data", data) }
        memory[key] = now to box
        file?.let { f -> runCatching { f.writeText(box.toString()) } }
        data
    }

    /** One ws/2 call at most every [SPACING_MS]; null for 404, IOException for the rest. */
    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject? = gate.withLock {
        NetworkGate.blockReason(Feature.MUSICBRAINZ)?.let { throw NetworkBlockedException(Feature.MUSICBRAINZ, it) }
        val url = (BASE + path).toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("fmt", "json").build()
        repeat(2) { attempt ->
            val wait = lastAt + SPACING_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                val req = NetworkGate.request(url.toString(), Feature.MUSICBRAINZ).header("User-Agent", userAgent).header("Accept", "application/json").build()
                NetworkGate.client.newCall(req).execute().use { r ->
                    when {
                        r.code == 404 -> return@withLock null
                        r.code == 503 && attempt == 0 -> delay(2_000)
                        !r.isSuccessful -> throw IOException("MusicBrainz answered ${r.code}")
                        else -> return@withLock runCatching { JSONObject(r.body.string()) }.getOrElse { throw IOException("MusicBrainz sent something unreadable") }
                    }
                }
            } finally {
                lastAt = System.currentTimeMillis()
            }
        }
        throw IOException("MusicBrainz is busy")
    }
}
