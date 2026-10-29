package com.slowva.player.data

import android.content.Context
import android.net.Uri
import com.slowva.player.SlowvaApp
import com.slowva.player.data.db.RatingEntity
import com.slowva.player.data.db.RatingKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a RateYourMusic import did; [likedArtists] are artists with an album at 4 stars or more. */
data class RymResult(val imported: Int, val skipped: Int, val matched: Int, val likedArtists: Int, val reviews: Int)

/** One album line of a RateYourMusic export. */
data class RymRow(val artist: String, val album: String, val year: Int?, val rating: Int, val ownership: String, val review: String?)

/** RateYourMusic's user export (CSV): album ratings 0..10 and reviews. */
object Rym {
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun read(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            require(out.size() <= MAX_BYTES) { "That file is too large" }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** RFC 4180 CSV: quoted fields may hold commas, newlines and doubled quotes; a BOM is skipped. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('﻿')) 1 else 0
        fun endField() { row += field.toString(); field.setLength(0) }
        fun endRow() { endField(); if (row.size > 1 || row[0].isNotEmpty()) rows += row; row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Album rows, columns found by header name so extra or reordered columns don't matter. */
    fun rows(text: String): List<RymRow> {
        val all = parseCsv(text)
        val header = all.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        fun col(name: String) = header.indexOf(name.lowercase())
        val first = col("First Name"); val last = col("Last Name")
        val firstLoc = col("First Name localized"); val lastLoc = col("Last Name localized")
        val title = col("Title"); val date = col("Release_Date"); val rating = col("Rating")
        val own = col("Ownership"); val review = col("Review")
        require(title >= 0 && rating >= 0 && (first >= 0 || last >= 0)) { "Not a RateYourMusic export" }
        return all.drop(1).map { r ->
            fun at(i: Int) = r.getOrNull(i)?.trim().orEmpty()
            val name = listOf(at(first), at(last)).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { listOf(at(firstLoc), at(lastLoc)).filter { it.isNotEmpty() }.joinToString(" ") }
            RymRow(
                artist = name,
                album = at(title),
                year = Regex("(19|20)\\d\\d").find(at(date))?.value?.toInt(),
                rating = at(rating).toIntOrNull() ?: 0,
                ownership = at(own),
                review = at(review).ifEmpty { null },
            )
        }
    }

    /** Album rating rows; unrated lines without a review, lines without a name and ratings past 10 are skipped. */
    fun toRatings(rows: List<RymRow>, now: Long): List<RatingEntity> =
        rows.mapNotNull { r ->
            if (r.artist.isEmpty() || r.rating !in 0..10 || (r.rating == 0 && r.review == null)) return@mapNotNull null
            val key = RatingKeys.album(r.artist, r.album) ?: return@mapNotNull null
            RatingEntity(RatingKind.ALBUM, key, r.rating, r.review?.take(2000), now, r.album, r.artist, r.album, r.year)
        }.associateBy { it.key }.values.toList()

    /** Artists with an album rated 4 stars or more. */
    fun likedArtists(ratings: List<RatingEntity>): Set<String> =
        ratings.filter { it.kind == RatingKind.ALBUM && it.value >= Ratings.HIGH }.mapTo(HashSet()) { RatingKeys.artist(it.artist) }
}

/** Reads a RateYourMusic export into album ratings and reviews. */
suspend fun importRym(context: Context, uri: Uri): RymResult = withContext(Dispatchers.IO) {
    val app = context.applicationContext as SlowvaApp
    val text = app.contentResolver.openInputStream(uri)?.use(Rym::read) ?: error("Can't read that file")
    val rows = Rym.rows(text)
    val ratings = Rym.toRatings(rows, System.currentTimeMillis())
    Ratings.get(app).importRows(ratings)
    val local = app.repository.tracks.value.mapNotNullTo(HashSet()) { RatingKeys.album(it.artist, it.album) }
    RymResult(
        imported = ratings.size,
        skipped = rows.size - ratings.size,
        matched = ratings.count { it.key in local },
        likedArtists = Rym.likedArtists(ratings).size,
        reviews = ratings.count { it.review != null },
    )
}
