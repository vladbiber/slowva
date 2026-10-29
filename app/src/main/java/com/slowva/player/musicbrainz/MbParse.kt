package com.slowva.player.musicbrainz

import com.slowva.player.data.RatingKeys
import com.slowva.player.data.Track
import org.json.JSONArray
import org.json.JSONObject

/** One track of a MusicBrainz release; [lengthMs] 0 when unknown. */
data class MbTrack(val disc: Int, val position: Int, val title: String, val lengthMs: Long)

/** A release group (album, EP, single) as listed in search results and discographies. */
data class MbGroup(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val primaryType: String?,
    val secondaryTypes: List<String>,
    val score: Int = 0,
) {
    val coverUrl: String get() = MusicBrainz.coverUrl(id)
}

/** What the album page shows: the group, its community rating and genres, and one release's tracks. */
data class MbAlbum(
    val group: MbGroup,
    /** 0..5, null without votes */
    val rating: Double?,
    val votes: Int,
    val tags: List<String>,
    val tracks: List<MbTrack>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", group.id).put("title", group.title).put("artist", group.artist)
        .put("year", group.year ?: JSONObject.NULL).put("type", group.primaryType ?: JSONObject.NULL)
        .put("secondary", JSONArray(group.secondaryTypes))
        .put("rating", rating ?: JSONObject.NULL).put("votes", votes)
        .put("tags", JSONArray(tags))
        .put("tracks", JSONArray(tracks.map { JSONObject().put("d", it.disc).put("p", it.position).put("t", it.title).put("l", it.lengthMs) }))

    companion object {
        fun fromJson(o: JSONObject) = MbAlbum(
            MbGroup(
                o.getString("id"), o.getString("title"), o.optString("artist"),
                o.optInt("year").takeIf { o.has("year") && !o.isNull("year") },
                o.optString("type").takeIf { !o.isNull("type") && it.isNotEmpty() },
                strings(o.optJSONArray("secondary")),
            ),
            o.optDouble("rating").takeIf { !o.isNull("rating") && !it.isNaN() },
            o.optInt("votes"),
            strings(o.optJSONArray("tags")),
            objects(o.optJSONArray("tracks")).map { MbTrack(it.optInt("d", 1), it.getInt("p"), it.getString("t"), it.optLong("l")) },
        )
    }
}

/** Pure readers of ws/2 JSON and the matching rules, kept apart from the network for tests. */
object MbParse {
    private val VERSION_WORDS = "remaster|remastered|live|version|edit|mix|mono|stereo|demo|bonus|deluxe|edition|explicit|clean|acoustic|instrumental|single|radio|feat|ft|featuring|official|audio|video|lyrics"
    private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*\\b($VERSION_WORDS)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex("\\s+-\\s+[^-]*\\b($VERSION_WORDS)\\b.*$", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val SKIP_SECONDARY = setOf("compilation", "live", "remix", "dj-mix", "demo", "interview", "spokenword", "audiobook", "audio drama", "field recording")

    /** A title without edition, remaster or "feat." notes, normalised like rating keys. */
    fun titleKey(s: String): String = RatingKeys.norm(s.replace(BRACKETS, "").replace(DASH_TAIL, "").replace(FEAT, "")).ifEmpty { RatingKeys.norm(s) }

    fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it > 1000 }

    fun groups(o: JSONObject): List<MbGroup> = objects(o.optJSONArray("release-groups")).mapNotNull(::group)

    fun group(g: JSONObject): MbGroup? {
        val id = g.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return MbGroup(
            id = id,
            title = g.optString("title"),
            artist = credit(g.optJSONArray("artist-credit")),
            year = year(g.optString("first-release-date")),
            primaryType = g.optString("primary-type").takeIf { it.isNotEmpty() && !g.isNull("primary-type") },
            secondaryTypes = strings(g.optJSONArray("secondary-types")),
            score = g.optInt("score"),
        )
    }

    /** "Artist A feat. Artist B" from an artist-credit array. */
    fun credit(a: JSONArray?): String = objects(a).joinToString("") { it.optString("name").ifEmpty { it.optJSONObject("artist")?.optString("name").orEmpty() } + it.optString("joinphrase") }.trim()

    /** The search hit that is really [album] by [artist], or null when none is close enough. */
    fun best(candidates: List<MbGroup>, artist: String, album: String): MbGroup? {
        val wantTitle = titleKey(album)
        val wantArtist = RatingKeys.artist(artist)
        return candidates.mapNotNull { g ->
            val key = titleKey(g.title)
            val titleSame = key == wantTitle
            val titleClose = titleSame || key.isNotEmpty() && wantTitle.isNotEmpty() && (key.startsWith(wantTitle) || wantTitle.startsWith(key))
            val credit = RatingKeys.norm(g.artist)
            val artistSame = wantArtist.isEmpty() || credit == wantArtist || credit.split(' ').windowed(wantArtist.split(' ').size).any { it.joinToString(" ") == wantArtist }
            if (!artistSame || !titleClose || !titleSame && g.score < 95) return@mapNotNull null
            var points = g.score + (if (titleSame) 50 else 0)
            if (g.primaryType.equals("Album", true)) points += 10
            if (g.secondaryTypes.isEmpty()) points += 5
            g to points
        }.maxByOrNull { it.second }?.first
    }

    /** Rating, votes and up to six genres of a release-group lookup with inc=ratings+genres+tags. */
    fun rating(o: JSONObject): Pair<Double?, Int> {
        val r = o.optJSONObject("rating") ?: return null to 0
        val votes = r.optInt("votes-count")
        val v = if (r.isNull("value")) null else r.optDouble("value").takeIf { !it.isNaN() }
        return (if (votes > 0) v else null) to votes
    }

    fun tags(o: JSONObject): List<String> {
        val genres = objects(o.optJSONArray("genres"))
        val src = genres.ifEmpty { objects(o.optJSONArray("tags")) }
        return src.filter { it.optInt("count") > 0 || genres.isNotEmpty() }
            .sortedByDescending { it.optInt("count") }
            .map { it.optString("name").lowercase() }.filter { it.isNotBlank() }.distinct().take(6)
    }

    /** The release to read tracks from: official first, then the earliest. */
    fun pickRelease(o: JSONObject): String? = objects(o.optJSONArray("releases"))
        .filter { it.optString("id").isNotEmpty() }
        .sortedWith(compareBy<JSONObject>({ if (it.optString("status").equals("Official", true)) 0 else 1 }, { it.optString("date").ifEmpty { "9999" } }))
        .firstOrNull()?.optString("id")

    fun tracks(release: JSONObject): List<MbTrack> = objects(release.optJSONArray("media")).flatMapIndexed { i, m ->
        val disc = m.optInt("position", i + 1)
        objects(m.optJSONArray("tracks")).mapIndexed { j, t ->
            val rec = t.optJSONObject("recording")
            val len = if (t.isNull("length")) rec?.optLong("length") ?: 0L else t.optLong("length")
            MbTrack(disc, t.optInt("position", j + 1), t.optString("title").ifEmpty { rec?.optString("title").orEmpty() }, len)
        }
    }

    /** The artist id from an artist search, only for a name match. */
    fun artistId(o: JSONObject, name: String): String? {
        val want = RatingKeys.artist(name)
        val hits = objects(o.optJSONArray("artists"))
        return hits.filter { RatingKeys.norm(it.optString("name")) == want || RatingKeys.norm(it.optString("sort-name")) == want }
            .maxByOrNull { it.optInt("score") }?.optString("id")
            ?: hits.firstOrNull()?.takeIf { it.optInt("score") == 100 && RatingKeys.norm(it.optString("name")).contains(want) }?.optString("id")
    }

    /** Studio albums oldest first, then EPs; compilations, live and remix records are left out. */
    fun discography(o: JSONObject): List<MbGroup> = groups(o)
        .filter { g -> g.secondaryTypes.none { it.lowercase() in SKIP_SECONDARY } }
        .distinctBy { titleKey(it.title) }
        .let(::albumsFirst)

    fun albumsFirst(groups: List<MbGroup>) = groups.sortedWith(compareBy({ if (it.primaryType.equals("Album", true)) 0 else 1 }, { it.year ?: 9999 }, { it.title }))

    /** Index in [mb] to your song with the same title; songs on this album win over others. */
    fun matchLocal(mb: List<MbTrack>, local: List<Track>, album: String): Map<Int, Track> {
        val albumKey = titleKey(album)
        val byTitle = local.sortedBy { if (titleKey(it.album) == albumKey) 0 else 1 }.groupBy { titleKey(it.title) }
        return mb.withIndex().mapNotNull { (i, t) -> byTitle[titleKey(t.title)]?.firstOrNull()?.let { i to it } }.toMap()
    }

    fun lengthLabel(ms: Long): String = if (ms <= 0) "" else "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
}

internal fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
internal fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
