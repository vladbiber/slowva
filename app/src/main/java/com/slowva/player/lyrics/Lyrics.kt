package com.slowva.player.lyrics

import com.slowva.player.data.Track
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

/** Timed lines when the song has them, otherwise the plain text; both null means none were found. */
data class Lyrics(val synced: List<LyricLine>?, val plain: String?) {
    val found: Boolean get() = !synced.isNullOrEmpty() || !plain.isNullOrBlank()

    companion object {
        val NONE = Lyrics(null, null)
    }
}

object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val stamped = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+)(.*)$""")

    /** "[01:02.34][02:10.00] text" lines to time-ordered lines; tags like [ar:...] are dropped. */
    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
        val m = stamped.find(line.trim()) ?: return@flatMap emptySequence()
        val text = m.groupValues[2].trim()
        stamp.findAll(m.groupValues[1]).map { t ->
            val (min, sec, frac) = t.destructured
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + frac.padEnd(3, '0').toLong(), text)
        }
    }.sortedBy { it.timeMs }.toList()

    /** The line playing at [positionMs]: the last one that has started, or -1 before the first. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }
}

/** Lyrics from LRCLIB (lrclib.net, free, no key), kept in memory per track for the session. */
object LyricsRepository {
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private const val UA = "slowva (github.com/vladbiber/slowva)"
    private val api = "https://lrclib.net/api/".toHttpUrl()

    fun cached(track: Track): Lyrics? = cache[track.id]

    /** Exact match first, then a search; a network failure returns NONE without caching it. */
    suspend fun get(track: Track): Lyrics {
        cache[track.id]?.let { return it }
        val sec = track.durationMs / 1000
        val result = runCatching {
            val exact = fetch(api.newBuilder().addPathSegment("get")
                .addQueryParameter("track_name", track.title)
                .addQueryParameter("artist_name", track.artist)
                .addQueryParameter("album_name", track.album)
                .addQueryParameter("duration", sec.toString()).build())
                ?.let { from(JSONObject(it)) }
            exact?.takeIf { it.found } ?: fetch(api.newBuilder().addPathSegment("search")
                .addQueryParameter("track_name", track.title)
                .addQueryParameter("artist_name", track.artist).build())
                ?.let { best(JSONArray(it), sec) }
                ?: Lyrics.NONE
        }.getOrNull() ?: return Lyrics.NONE
        cache[track.id] = result
        return result
    }

    /** null on 404; other failures throw. */
    private suspend fun fetch(url: HttpUrl): String? = withContext(Dispatchers.IO) {
        val req = NetworkGate.request(url.toString(), Feature.LYRICS).header("User-Agent", UA).build()
        NetworkGate.client.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> error("LRCLIB answered ${r.code}")
                else -> r.body?.string()
            }
        }
    }

    private fun from(o: JSONObject): Lyrics {
        if (o.optBoolean("instrumental")) return Lyrics.NONE
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let(Lrc::parse)?.takeIf { it.isNotEmpty() }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return Lyrics(synced, plain)
    }

    /** Synced lyrics of a close length beat any synced, which beat plain. */
    private fun best(a: JSONArray, sec: Long): Lyrics? {
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        val close = all.filter { abs(it.optDouble("duration", 0.0) - sec) <= 5 }
        return (close + all).map(::from).let { l -> l.firstOrNull { it.synced != null } ?: l.firstOrNull { it.found } }
    }
}
