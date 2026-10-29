package com.slowva.player.recommend

import com.slowva.player.data.RatingKeys
import com.slowva.player.data.Ratings
import com.slowva.player.data.Track
import com.slowva.player.data.db.RatingEntity
import com.slowva.player.data.db.RatingKind
import com.slowva.player.data.db.RatingTagEntity
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Something the listener starred, used to seed a song radio. [track] is the song to start from; without one
 * the radio starts from an online search for [title] (an album) or the artist's top song.
 */
data class StarSeed(
    val key: String,
    val title: String,
    val artist: String,
    val label: String,
    val theme: String,
    val weight: Double,
    val track: Track? = null,
)

/** One labelled row of the "From your stars" shelf. */
data class StarGroup(val label: String, val seeds: List<String>, val songs: List<Song>)

/** Pure rules of the "From your stars" shelf: which seeds, and which of their radio songs survive. */
object Stars {
    const val TOTAL = 25
    const val PER_ARTIST = 2
    const val THEMES = 3
    const val PER_THEME = 2

    /** 1 for a rating made today, 0.5 for one long ago. */
    fun recency(ratedAt: Long, now: Long): Double = 0.5 + 0.5 * exp(-(now - ratedAt).coerceAtLeast(0) / (120.0 * 86_400_000))

    /**
     * Seeds from songs rated 4 stars and up, favourites (weaker), and songs of albums and artists rated 4 stars and up.
     * Each "Not for me" on a seed's songs halves its weight.
     */
    fun seeds(
        ratings: List<RatingEntity>,
        tags: List<RatingTagEntity>,
        library: List<Track>,
        favorites: List<Track>,
        penalties: Map<String, Int>,
        now: Long,
    ): List<StarSeed> {
        val tagOf = HashMap<String, String>()
        tags.sortedBy { it.tag }.forEach { tagOf.putIfAbsent("${it.kind}:${it.key}", it.tag) }
        fun theme(t: Track?, artist: String, album: String?, songKey: String?): String =
            songKey?.let { tagOf["${RatingKind.SONG}:$it"] }
                ?: album?.let { RatingKeys.album(artist, it) }?.let { tagOf["${RatingKind.ALBUM}:$it"] }
                ?: tagOf["${RatingKind.ARTIST}:${RatingKeys.artist(artist)}"]
                ?: ("artist:" + RadioMixer.artistKey(t?.artist ?: artist))
        val bySongKey = library.associateBy { RatingKeys.song(it.id) }
        val out = LinkedHashMap<String, StarSeed>()
        fun add(s: StarSeed) {
            val w = s.weight * 0.5.pow(penalties[s.key] ?: 0)
            val old = out[s.key]
            if (old == null || old.weight < w) out[s.key] = s.copy(weight = w)
        }
        val high = ratings.filter { it.value >= Ratings.HIGH }
        for (r in high.filter { it.kind == RatingKind.SONG }) {
            val t = bySongKey[r.key]
            add(StarSeed("song:${r.key}", r.title, r.artist, r.title, theme(t, r.artist, r.album, r.key), r.value / 5.0 * recency(r.ratedAt, now), t))
        }
        for (t in favorites) {
            val k = RatingKeys.song(t.id)
            if ("song:$k" in out) continue
            add(StarSeed("song:$k", t.title, t.artist, t.title, theme(t, t.artist, t.album, k), 0.75, t))
        }
        val albums = library.groupBy { RatingKeys.album(it.artist, it.album) }
        val artists = library.groupBy { RatingKeys.artist(it.artist) }
        for (r in high.filter { it.kind == RatingKind.ALBUM }) {
            val base = r.value / 10.0 * recency(r.ratedAt, now)
            val songs = albums[r.key].orEmpty()
            if (songs.isEmpty()) add(StarSeed("album:${r.key}", r.title, r.artist, r.title, theme(null, r.artist, r.title, null), base * 0.6))
            songs.take(2).forEach { t ->
                add(StarSeed("song:${RatingKeys.song(t.id)}", t.title, t.artist, r.title, theme(t, t.artist, t.album, RatingKeys.song(t.id)), base * 0.8, t))
            }
        }
        for (r in high.filter { it.kind == RatingKind.ARTIST }) {
            val base = r.value / 10.0 * recency(r.ratedAt, now)
            val songs = artists[r.key].orEmpty()
            if (songs.isEmpty()) add(StarSeed("artist:${r.key}", "", r.artist, r.artist, theme(null, r.artist, null, null), base * 0.6))
            songs.take(2).forEach { t ->
                add(StarSeed("song:${RatingKeys.song(t.id)}", t.title, t.artist, r.artist, theme(t, t.artist, t.album, RatingKeys.song(t.id)), base * 0.7, t))
            }
        }
        return out.values.sortedByDescending { it.weight }
    }

    /**
     * Up to [themes] themes, each with up to [perTheme] seeds, drawn by weight. Seeds used last time ([last])
     * are left out while enough others remain, so the shelf rotates.
     */
    fun pick(seeds: List<StarSeed>, last: Set<String>, random: Random, themes: Int = THEMES, perTheme: Int = PER_THEME): List<List<StarSeed>> {
        val fresh = seeds.filter { it.key !in last }
        val pool = if (fresh.map { it.theme }.distinct().size >= themes.coerceAtMost(seeds.map { it.theme }.distinct().size)) fresh else seeds
        val byTheme = pool.groupBy { it.theme }.toMutableMap()
        val out = ArrayList<List<StarSeed>>()
        while (out.size < themes && byTheme.isNotEmpty()) {
            val theme = draw(byTheme.keys.toList(), random) { t -> byTheme.getValue(t).maxOf { it.weight } } ?: break
            val members = byTheme.remove(theme)!!.toMutableList()
            val chosen = ArrayList<StarSeed>()
            while (chosen.size < perTheme && members.isNotEmpty()) {
                val s = draw(members, random) { it.weight } ?: break
                members.remove(s)
                chosen += s
            }
            out += chosen
        }
        return out
    }

    private fun <T> draw(items: List<T>, random: Random, weight: (T) -> Double): T? {
        val total = items.sumOf { weight(it).coerceAtLeast(0.0) }
        if (items.isEmpty()) return null
        if (total <= 0.0) return items[random.nextInt(items.size)]
        var r = random.nextDouble() * total
        for (it in items) {
            r -= weight(it).coerceAtLeast(0.0)
            if (r <= 0) return it
        }
        return items.last()
    }

    /** "Because you starred X" */
    fun label(group: List<StarSeed>) = "Because you starred " + (group.firstOrNull()?.label?.ifBlank { null } ?: group.firstOrNull()?.artist.orEmpty())

    /** Songs the listener already has; matched by song id or by [RadioMixer.sameWork]. */
    class Known(songs: List<Song>) {
        private val ids = songs.mapTo(HashSet()) { it.id }
        private val pairs = HashSet<String>()
        private val works = HashSet<String>()
        private val versionWorks = HashSet<String>()

        init {
            for (s in songs) {
                val w = RadioMixer.work(s.title).ifEmpty { continue }
                pairs += w + "|" + RadioMixer.artistKey(s.artist)
                works += w
                if (RadioMixer.isVersion(s.title)) versionWorks += w
            }
        }

        fun has(s: Song): Boolean {
            if (s.id in ids) return true
            val w = RadioMixer.work(s.title).ifEmpty { return false }
            return w + "|" + RadioMixer.artistKey(s.artist) in pairs || w in versionWorks || (RadioMixer.isVersion(s.title) && w in works)
        }
    }

    /**
     * Cleans each group's radio and shares [total] songs between the groups: no [known] songs or other versions,
     * no [hidden] or [low] rated artists ([RadioMixer.artistKey]s), at most [perArtist] songs per artist,
     * no song twice, and no artist twice in a row inside a group. Groups keep their places; some may come back empty.
     */
    fun curate(
        groups: List<List<Song>>,
        known: Known,
        hidden: Set<String>,
        low: Set<String>,
        played: Set<String> = emptySet(),
        total: Int = TOTAL,
        perArtist: Int = PER_ARTIST,
    ): List<List<Song>> {
        val blocked = hidden + low
        val cleaned = groups.map { g -> RadioMixer.clean(emptyList(), g, played, blocked).filterNot(known::has) }
        val taken = ArrayList<Song>()
        val perArtistCount = HashMap<String, Int>()
        val out = cleaned.map { ArrayList<Song>() }
        val next = IntArray(cleaned.size)
        var moved = true
        while (taken.size < total && moved) {
            moved = false
            for (g in cleaned.indices) {
                if (taken.size >= total) break
                val list = cleaned[g]
                while (next[g] < list.size) {
                    val s = list[next[g]++]
                    val a = RadioMixer.artistKey(s.artist)
                    if ((perArtistCount[a] ?: 0) >= perArtist) continue
                    if (taken.any { it.id == s.id || RadioMixer.sameWork(it, s) }) continue
                    perArtistCount[a] = (perArtistCount[a] ?: 0) + 1
                    taken += s
                    out[g] += s
                    moved = true
                    break
                }
            }
        }
        return out.map(::noBackToBack)
    }

    /** Keeps the order but never plays one artist twice running when another can go between. */
    fun noBackToBack(songs: List<Song>): List<Song> {
        val pool = songs.toMutableList()
        val out = ArrayList<Song>(songs.size)
        while (pool.isNotEmpty()) {
            val prev = out.lastOrNull()?.let { RadioMixer.artistKey(it.artist) }
            val i = pool.indexOfFirst { RadioMixer.artistKey(it.artist) != prev }
            out += pool.removeAt(if (i < 0) 0 else i)
        }
        return out
    }

    /** One list from all groups, a song from each in turn. */
    fun flatten(groups: List<List<Song>>): List<Song> {
        val out = ArrayList<Song>()
        for (i in 0 until (groups.maxOfOrNull { it.size } ?: 0)) groups.forEach { g -> g.getOrNull(i)?.let(out::add) }
        return noBackToBack(out)
    }

    /** Keys of everything rated 4 stars and up; the shelf is rebuilt early when this moves by [MOVED] or more. */
    fun fingerprint(ratings: List<RatingEntity>): Set<Int> =
        ratings.filter { it.value >= Ratings.HIGH }.mapTo(HashSet()) { "${it.kind}:${it.key}".hashCode() }

    const val MOVED = 5

    fun movedALot(old: Set<Int>, new: Set<Int>) = (old - new).size + (new - old).size >= MOVED
}
