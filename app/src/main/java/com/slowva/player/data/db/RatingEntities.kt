package com.slowva.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

enum class RatingKind { SONG, ALBUM, ARTIST }

/**
 * A personal rating: [value] 1..10 in half stars (10 = 5 stars), 0 when only a review is kept.
 * [key] comes from [com.slowva.player.data.RatingKeys]; the names are kept so lists show without the song.
 */
@Entity(tableName = "ratings", primaryKeys = ["kind", "key"], indices = [Index("value"), Index("year")])
data class RatingEntity(
    val kind: RatingKind,
    val key: String,
    val value: Int,
    val review: String? = null,
    val ratedAt: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
)

/** A personal genre tag on a song, album or artist. */
@Entity(tableName = "rating_tags", primaryKeys = ["kind", "key", "tag"], indices = [Index("tag")])
data class RatingTagEntity(
    val kind: RatingKind,
    val key: String,
    val tag: String,
)

@Dao
interface RatingsDao {
    @Query("SELECT * FROM ratings WHERE kind = :kind AND `key` = :key")
    fun observe(kind: RatingKind, key: String): Flow<RatingEntity?>

    @Query("SELECT * FROM ratings WHERE kind = :kind AND `key` = :key")
    suspend fun get(kind: RatingKind, key: String): RatingEntity?

    @Query("SELECT * FROM ratings ORDER BY ratedAt DESC")
    fun all(): Flow<List<RatingEntity>>

    @Query("SELECT * FROM ratings")
    suspend fun allNow(): List<RatingEntity>

    @Query("SELECT * FROM ratings WHERE kind = :kind AND value > 0 AND (:year IS NULL OR year = :year) ORDER BY value DESC, ratedAt DESC LIMIT :limit")
    fun top(kind: RatingKind, year: Int?, limit: Int): Flow<List<RatingEntity>>

    @Upsert
    suspend fun upsert(rows: List<RatingEntity>)

    @Query("DELETE FROM ratings WHERE kind = :kind AND `key` = :key")
    suspend fun delete(kind: RatingKind, key: String)

    @Query("SELECT year FROM tracks WHERE id = :trackId")
    suspend fun trackYear(trackId: String): Int?

    @Query("SELECT tag FROM rating_tags WHERE kind = :kind AND `key` = :key ORDER BY tag")
    fun tags(kind: RatingKind, key: String): Flow<List<String>>

    @Query("SELECT * FROM rating_tags")
    suspend fun allTags(): List<RatingTagEntity>

    @Upsert
    suspend fun addTag(tag: RatingTagEntity)

    @Query("DELETE FROM rating_tags WHERE kind = :kind AND `key` = :key AND tag = :tag")
    suspend fun removeTag(kind: RatingKind, key: String, tag: String)

    /** Sets the stars and keeps the review; [value] 0 clears them and drops the row when no review is left. */
    @Transaction
    suspend fun setValue(row: RatingEntity) {
        val old = get(row.kind, row.key)
        val merged = row.copy(review = row.review ?: old?.review, year = row.year ?: old?.year)
        if (merged.value == 0 && merged.review.isNullOrBlank()) delete(row.kind, row.key) else upsert(listOf(merged))
    }

    /** Sets the review and keeps the stars; a blank review on an unrated row drops it. */
    @Transaction
    suspend fun setReview(row: RatingEntity) {
        val old = get(row.kind, row.key)
        val merged = (old ?: row).copy(review = row.review?.takeIf { it.isNotBlank() })
        if (merged.value == 0 && merged.review == null) delete(row.kind, row.key) else upsert(listOf(merged))
    }
}
