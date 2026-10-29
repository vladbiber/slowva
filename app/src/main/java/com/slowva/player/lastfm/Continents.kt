package com.slowva.player.lastfm

import com.slowva.player.recommend.HiddenArtists
import com.slowva.player.recommend.RadioMixer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hand-picked Last.fm genre tags and the biggest Last.fm countries of each continent (ISO names,
 * as geo.* wants them). Every tag was checked to return songs from tag.getTopTracks.
 */
enum class Continent(val label: String, val countries: List<String>, val tags: List<String>) {
    AFRICA(
        "Africa", listOf("Nigeria", "South Africa", "Kenya", "Ghana", "Egypt"),
        listOf("afrobeats", "amapiano", "afrobeat", "highlife", "bongo flava", "gqom", "soukous", "rai"),
    ),
    ASIA(
        "Asia", listOf("Japan", "India", "Korea, Republic of", "Indonesia", "Philippines"),
        listOf("k-pop", "j-pop", "j-rock", "c-pop", "bollywood", "city pop", "t-pop", "opm"),
    ),
    EUROPE(
        "Europe", listOf("Germany", "United Kingdom", "France", "Spain", "Italy"),
        listOf("schlager", "chanson", "italo disco", "french house", "eurodance", "nordic folk", "britpop", "flamenco"),
    ),
    NORTH_AMERICA(
        "North America", listOf("United States", "Mexico", "Canada", "Jamaica"),
        listOf("hip-hop", "country", "rnb", "regional mexican", "corridos", "reggae", "dancehall", "soca"),
    ),
    SOUTH_AMERICA(
        "South America", listOf("Brazil", "Argentina", "Colombia", "Chile"),
        listOf("mpb", "bossa nova", "samba", "sertanejo", "funk carioca", "reggaeton", "cumbia", "tango"),
    ),
    // shortcut: only two countries have real Last.fm charts here; add more if Last.fm grows there
    OCEANIA(
        "Oceania", listOf("Australia", "New Zealand"),
        listOf("australian", "oz rock", "aussie hip hop", "australian rock", "new zealand", "flying nun", "maori", "polynesian"),
    ),
}

/** Continent charts and artists, built from the country ones since Last.fm has none per continent. */
object WorldCharts {
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * One chart from several: a song scores its listeners over its country's top song's, summed over
     * countries, so a big country can't drown the rest and hits shared across countries rise.
     * Songs by the region's own artists ([local]) count triple and worldwide stars ([global]) a third,
     * otherwise every continent's list opens with the same global hits.
     */
    fun mergeCharts(
        charts: List<List<Pair<LastFm.Song, Long>>>, hidden: Set<String>, max: Int = 100,
        local: Set<String> = emptySet(), global: Set<String> = emptySet(),
    ): List<LastFm.Song> {
        val localKeys = local.mapTo(HashSet()) { RadioMixer.artistKey(it) }
        val globalKeys = global.mapTo(HashSet()) { RadioMixer.artistKey(it) }
        val scores = LinkedHashMap<String, Pair<LastFm.Song, Double>>()
        for (chart in charts) {
            val top = (chart.maxOfOrNull { it.second } ?: continue).coerceAtLeast(1)
            for ((song, listeners) in chart) {
                val key = RadioMixer.artistKey(song.artist) + "|" + RadioMixer.work(song.title).ifEmpty { song.title.lowercase() }
                val old = scores[key]
                val a = RadioMixer.artistKey(song.artist)
                val weight = if (a in globalKeys) 1 / 3.0 else if (a in localKeys) 3.0 else 1.0
                scores[key] = (old?.first ?: song) to (old?.second ?: 0.0) + weight * listeners / top
            }
        }
        return HiddenArtists.filter(scores.values.sortedByDescending { it.second }.map { it.first }, hidden) { it.artist }.take(max)
    }

    /** The countries' artists taken in turns, once each, with the region's own before the worldwide ones. */
    fun mergeArtists(lists: List<List<LastFm.Artist>>, global: Set<String>): List<LastFm.Artist> {
        val turns = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        val everywhere = global.mapTo(HashSet()) { it.lowercase() }
        return turns.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() in everywhere }
    }

    suspend fun artists(c: Continent): List<LastFm.Artist> = coroutineScope {
        val global = async { orEmpty { LastFm.globalArtists() } }
        val lists = c.countries.map { async { orEmpty { LastFm.countryArtists(it) } } }.awaitAll()
        mergeArtists(lists, global.await().mapTo(HashSet()) { it.name })
    }

    private fun file(dir: File, c: Continent) = File(dir, "top100v2_${c.name.lowercase()}.txt")  // v2: regional weighting

    /** The saved Top 100 and whether it is over a week old; null when none is saved. */
    suspend fun cached(dir: File, c: Continent): Pair<List<LastFm.Song>, Boolean>? = withContext(Dispatchers.IO) {
        val f = file(dir, c).takeIf { it.exists() } ?: return@withContext null
        val songs = runCatching { f.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { LastFm.Song(it[0], it[1]) } }
        songs.takeIf { it.isNotEmpty() }?.let { it to (System.currentTimeMillis() - f.lastModified() > WEEK_MS) }
    }

    /** A fresh Top 100, saved only when every country answered so an offline moment can't stick for a week. */
    suspend fun fetch(dir: File, c: Continent, hidden: Set<String>): List<LastFm.Song> = coroutineScope {
        val global = async { orEmpty { LastFm.globalArtists() }.mapTo(HashSet()) { it.name } }
        val local = c.countries.map { async { orEmpty { LastFm.countryArtists(it) } } }
        val charts = c.countries.map { async { orEmpty { LastFm.countryTracks(it) } } }.awaitAll()
        val everywhere = global.await()
        val own = local.awaitAll().flatten().map { it.name }.filterNot { it in everywhere }.toSet()
        val top = mergeCharts(charts, hidden, local = own, global = everywhere)
        if (charts.none { it.isEmpty() }) withContext(Dispatchers.IO) {
            runCatching { file(dir, c).writeText(top.joinToString("\n") { "${it.title.oneLine()}\t${it.artist.oneLine()}" }) }
        }
        top
    }

    private fun String.oneLine() = replace(Regex("[\t\n\r]"), " ")

    private suspend fun <T> orEmpty(load: suspend () -> List<T>): List<T> =
        try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}
