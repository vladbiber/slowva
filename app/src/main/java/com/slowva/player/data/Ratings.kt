package com.slowva.player.data

import android.content.Context
import com.slowva.player.SlowvaApp
import com.slowva.player.data.db.RatingEntity
import com.slowva.player.data.db.RatingKind
import com.slowva.player.data.db.RatingTagEntity
import com.slowva.player.data.db.RatingsDao
import com.slowva.player.recommend.RadioMixer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Stable keys for ratings, so tags from files, online songs and RateYourMusic meet on one row. */
object RatingKeys {
    /** Albums that stand for "no album" and are never rated. */
    private val NO_ALBUM = setOf("", "unknown album", "imported files", "<unknown>")
    private val EDITION = Regex("\\s*[(\\[][^)\\]]*(deluxe|remaster|edition|expanded|anniversary|bonus)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)

    /** Lower case, no accents or punctuation, single spaces. */
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace('&', ' ').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun song(trackId: String): String = trackId

    fun artist(name: String): String = norm(name.replace(FEAT, "").removeSuffix(" - Topic"))

    /** "artist|album", or null when [album] is a placeholder. */
    fun album(artist: String, album: String): String? {
        if (album.trim().lowercase() in NO_ALBUM) return null
        val a = norm(album.replace(EDITION, ""))
        return if (a.isEmpty()) null else artist(artist) + "|" + a
    }

    fun hasAlbum(t: Track) = album(t.artist, t.album) != null
}

/** 10 = 5 stars; shown as "3.5 stars". */
fun starsLabel(value: Int): String {
    val s = value / 2.0
    return (if (value % 2 == 0) "${value / 2}" else "$s") + if (value == 2) " star" else " stars"
}

/** Personal ratings, reviews and genre tags of songs, albums and artists; all local. */
class Ratings private constructor(private val dao: RatingsDao) {
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        writer.launch { dao.all().collect { rows -> publish(rows) } }
    }

    fun rating(kind: RatingKind, key: String): Flow<RatingEntity?> = dao.observe(kind, key)
    fun all(): Flow<List<RatingEntity>> = dao.all()
    suspend fun allNow(): List<RatingEntity> = dao.allNow()
    suspend fun allTags(): List<RatingTagEntity> = dao.allTags()

    /** Highest rated first; [year] only where the year is known. */
    fun top(kind: RatingKind, year: Int? = null, limit: Int = 50): Flow<List<RatingEntity>> = dao.top(kind, year, limit)
    fun topSongs(year: Int? = null, limit: Int = 50) = top(RatingKind.SONG, year, limit)
    fun topAlbums(year: Int? = null, limit: Int = 50) = top(RatingKind.ALBUM, year, limit)
    fun topArtists(limit: Int = 50) = top(RatingKind.ARTIST, null, limit)

    /** [value] 1..10, or 0 to clear the stars. */
    fun set(target: RateTarget, value: Int) {
        val v = value.coerceIn(0, 10)
        writer.launch {
            val year = target.year ?: target.trackId?.let { dao.trackYear(it) }
            dao.setValue(target.row(v, System.currentTimeMillis()).copy(year = year))
        }
    }

    fun clear(target: RateTarget) = set(target, 0)

    fun setReview(target: RateTarget, review: String) {
        writer.launch { dao.setReview(target.row(0, System.currentTimeMillis()).copy(review = review.trim().take(MAX_REVIEW))) }
    }

    fun tags(kind: RatingKind, key: String): Flow<List<String>> = dao.tags(kind, key)

    fun addTag(kind: RatingKind, key: String, tag: String) {
        val t = tag.trim().lowercase().take(40)
        if (t.isNotEmpty()) writer.launch { dao.addTag(RatingTagEntity(kind, key, t)) }
    }

    fun removeTag(kind: RatingKind, key: String, tag: String) { writer.launch { dao.removeTag(kind, key, tag) } }

    /** Imported rows, written in one go; existing reviews stay when the import has none. */
    suspend fun importRows(rows: List<RatingEntity>) {
        val old = dao.allNow().associateBy { it.kind to it.key }
        dao.upsert(rows.map { r -> old[r.kind to r.key]?.let { o -> r.copy(review = r.review ?: o.review, year = r.year ?: o.year) } ?: r })
    }

    private fun publish(rows: List<RatingEntity>) {
        val low = rows.filter { it.kind == RatingKind.ARTIST && it.value in 1..LOW }.mapTo(HashSet()) { RadioMixer.artistKey(it.artist) }
        _lowArtists.value = low
        RadioMixer.excluded = low
    }

    companion object {
        /** 4 stars and up */
        const val HIGH = 8
        /** 2 stars and below */
        const val LOW = 4
        private const val MAX_REVIEW = 2000

        private val _lowArtists = MutableStateFlow<Set<String>>(emptySet())
        /** [RadioMixer.artistKey]s of artists rated 2 stars or less; kept out of recommendations. */
        val lowArtists: StateFlow<Set<String>> = _lowArtists.asStateFlow()

        @Volatile private var instance: Ratings? = null
        fun get(context: Context): Ratings = instance ?: synchronized(this) {
            instance ?: Ratings((context.applicationContext as SlowvaApp).database.ratings()).also { instance = it }
        }
    }
}

/** Something to rate, with the names stored next to its rating. */
data class RateTarget(
    val kind: RatingKind,
    val key: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
    val trackId: String? = null,
) {
    fun row(value: Int, at: Long) = RatingEntity(kind, key, value, null, at, title, artist, album, year)

    companion object {
        fun song(t: Track) = RateTarget(RatingKind.SONG, RatingKeys.song(t.id), t.title, t.artist, t.album, trackId = t.id)
        fun album(t: Track) = RatingKeys.album(t.artist, t.album)?.let { RateTarget(RatingKind.ALBUM, it, t.album, t.artist, t.album) }
        fun artist(name: String) = RateTarget(RatingKind.ARTIST, RatingKeys.artist(name), name, name)
    }
}
