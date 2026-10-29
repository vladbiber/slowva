package com.slowva.player.recommend

import com.slowva.player.data.Track
import com.slowva.player.data.db.RatingEntity
import com.slowva.player.data.db.RatingKind
import com.slowva.player.data.db.RatingTagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StarsTest {
    private fun s(id: String, title: String, artist: String) = Song(id, title, artist)
    private fun t(id: String, title: String, artist: String, album: String = "Album") = Track(id, title, artist, album, "", null, 0L, isDemo = false)
    private val now = 1_000_000_000_000L

    @Test
    fun curateKeepsTwoPerArtistDropsKnownHiddenLowAndVersions() {
        val groups = listOf(
            listOf(s("1", "A1", "Ann"), s("2", "A2", "Ann"), s("3", "A3", "Ann"), s("4", "Known Song", "Kim"), s("5", "B1", "Bob")),
            listOf(s("6", "A1 (Live)", "Ann"), s("7", "H1", "Hidden Guy"), s("8", "L1", "Low Guy"), s("9", "C1", "Cat"), s("5", "B1", "Bob")),
        )
        val known = Stars.Known(listOf(s("x", "Known Song", "Kim")))
        val out = Stars.curate(groups, known, hidden = setOf("hidden guy"), low = setOf("low guy"))
        val all = out.flatten()
        assertEquals(2, all.count { it.artist == "Ann" })
        assertFalse(all.any { it.title == "Known Song" })
        assertFalse(all.any { it.artist == "Hidden Guy" || it.artist == "Low Guy" })
        assertFalse(all.any { it.title.contains("Live") })
        assertEquals(all.size, all.map { it.id }.distinct().size)
        assertEquals(2, out.size)
    }

    @Test
    fun curateCapsTotalAndSharesBetweenGroups() {
        val g1 = (1..40).map { s("a$it", "Song a$it", "Artist a$it") }
        val g2 = (1..40).map { s("b$it", "Song b$it", "Artist b$it") }
        val out = Stars.curate(listOf(g1, g2), Stars.Known(emptyList()), emptySet(), emptySet(), total = 25)
        assertEquals(25, out.sumOf { it.size })
        assertTrue(out.all { it.size >= 12 })
    }

    @Test
    fun noArtistBackToBackWhenAvoidable() {
        val out = Stars.noBackToBack(listOf(s("1", "a", "X"), s("2", "b", "X"), s("3", "c", "Y"), s("4", "d", "Z")))
        out.zipWithNext().forEach { (a, b) -> assertTrue(a.artist != b.artist) }
        assertEquals(4, out.size)
    }

    @Test
    fun knownMatchesVersionsAndVideoTitles() {
        val known = Stars.Known(listOf(s("k", "Blinding Lights", "The Weeknd")))
        assertTrue(known.has(s("z", "The Weeknd - Blinding Lights (Official Video)", "The Weeknd")))
        assertTrue(known.has(s("y", "Blinding Lights (Sped Up)", "someone")))
        assertTrue(known.has(s("k", "Other", "Other")))
        assertFalse(known.has(s("w", "Blinding Lights", "Other Band")))
    }

    @Test
    fun seedsWeighRatingsOverFavoritesAndSkipLowRatings() {
        val lib = listOf(t("dev_1", "Loved", "Ann"), t("dev_2", "Fav", "Bob"), t("dev_3", "Meh", "Cat"), t("dev_4", "Album Song", "Dan", "Great Album"))
        val ratings = listOf(
            RatingEntity(RatingKind.SONG, "dev_1", 10, null, now, "Loved", "Ann"),
            RatingEntity(RatingKind.SONG, "dev_3", 6, null, now, "Meh", "Cat"),
            RatingEntity(RatingKind.ALBUM, "dan|great album", 9, null, now, "Great Album", "Dan", "Great Album"),
            RatingEntity(RatingKind.ARTIST, "eve", 8, null, now, "Eve", "Eve"),
        )
        val seeds = Stars.seeds(ratings, emptyList(), lib, listOf(lib[1]), emptyMap(), now)
        assertEquals("song:dev_1", seeds.first().key)
        assertFalse(seeds.any { it.key == "song:dev_3" })
        assertTrue(seeds.any { it.key == "song:dev_2" && it.weight < seeds.first().weight })
        assertTrue(seeds.any { it.key == "song:dev_4" && it.label == "Great Album" && it.track != null })
        // an artist with no songs here is searched for by name
        assertTrue(seeds.any { it.key == "artist:eve" && it.track == null })
    }

    @Test
    fun notForMePenaltyAndPersonalTagsShapeSeeds() {
        val lib = listOf(t("dev_1", "One", "Ann"), t("dev_2", "Two", "Bob"))
        val ratings = listOf(
            RatingEntity(RatingKind.SONG, "dev_1", 10, null, now, "One", "Ann"),
            RatingEntity(RatingKind.SONG, "dev_2", 10, null, now, "Two", "Bob"),
        )
        val tags = listOf(RatingTagEntity(RatingKind.SONG, "dev_1", "shoegaze"), RatingTagEntity(RatingKind.ARTIST, "bob", "shoegaze"))
        val seeds = Stars.seeds(ratings, tags, lib, emptyList(), mapOf("song:dev_1" to 2), now)
        assertEquals("song:dev_2", seeds.first().key)
        assertEquals(0.25, seeds.first { it.key == "song:dev_1" }.weight / seeds.first().weight, 1e-9)
        assertTrue(seeds.all { it.theme == "shoegaze" })
    }

    @Test
    fun pickRotatesAwayFromLastSeeds() {
        val seeds = (1..6).map { StarSeed("song:$it", "T$it", "A$it", "T$it", "artist:a$it", 1.0) }
        val last = setOf("song:1", "song:2", "song:3")
        repeat(20) { r ->
            val picked = Stars.pick(seeds, last, Random(r))
            assertEquals(3, picked.size)
            assertTrue(picked.flatten().none { it.key in last })
        }
        // too few fresh themes: falls back to all seeds
        assertEquals(3, Stars.pick(seeds.take(3), last, Random(1)).size)
    }

    @Test
    fun ratingsMovedALot() {
        assertFalse(Stars.movedALot(setOf(1, 2, 3), setOf(1, 2, 3, 4)))
        assertTrue(Stars.movedALot(setOf(1, 2, 3), setOf(4, 5, 6)))
    }

    @Test
    fun cleanLeavesOutExcludedArtists() {
        val out = RadioMixer.clean(emptyList(), listOf(s("1", "a", "Low Guy feat. X"), s("2", "b", "Fine")), emptySet(), setOf("low guy"))
        assertEquals(listOf("2"), out.map { it.id })
    }
}
