package com.slowva.player.recommend

import android.content.Context
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Ratings
import com.slowva.player.data.Track
import com.slowva.player.data.db.RatingKind
import com.slowva.player.lastfm.LastFm
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

enum class Shelf(val title: String) {
    FOR_YOU("For you"),
    PLAYED("From what you played"),
    LIKED("From your stars"),
}

/**
 * Home's song-radio shelves: the [SongRadio.provider] radio of a few seed songs per shelf, cleaned by [RadioMixer].
 * Kept in memory and in a JSON file for [TTL_MS], so opening Home doesn't ask the network every time.
 * [Shelf.LIKED] is the flat form of [stars], rebuilt at most daily unless ratings moved a lot.
 */
object Recommendations {
    private const val TTL_MS = 12 * 60 * 60 * 1000L
    private const val STARS_TTL_MS = 24 * 60 * 60 * 1000L
    private const val RETRY_MS = 15 * 60 * 1000L
    private const val SEEDS = 3
    private const val PER_SHELF = 30
    // twice what is shown, so hidden artists leave no holes
    private const val KEEP = PER_SHELF * 2
    private const val MAX_REJECTED = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _shelves = MutableStateFlow<Map<Shelf, List<Song>>>(emptyMap())
    val shelves: StateFlow<Map<Shelf, List<Song>>> = combine(_shelves, HiddenArtists.names, Ratings.lowArtists) { all, hidden, low ->
        all.mapValues { (_, songs) -> keep(songs, hidden, low).take(PER_SHELF) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val _stars = MutableStateFlow<List<StarGroup>>(emptyList())
    /** "From your stars": labelled rows, "Because you starred X". */
    val stars: StateFlow<List<StarGroup>> = combine(_stars, HiddenArtists.names, Ratings.lowArtists) { groups, hidden, low ->
        groups.map { it.copy(songs = keep(it.songs, hidden, low)) }.filter { it.songs.isNotEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _building = MutableStateFlow(false)
    /** True while shelves are fetched from the network, so Home can show placeholders. */
    val building: StateFlow<Boolean> = _building.asStateFlow()
    private var savedAt = 0L
    private var starsAt = 0L
    private var triedAt = 0L
    private var loaded = false
    private var job: Job? = null
    private var file: File? = null
    private var lastSeeds = emptySet<String>()
    private var starPrint = emptySet<Int>()
    /** seed key to how many of its songs were "Not for me" */
    private val penalties = HashMap<String, Int>()
    /** videos dropped with "Not for me", never suggested again */
    private val rejected = LinkedHashSet<String>()
    private val lock = Any()

    private fun keep(songs: List<Song>, hidden: Set<String>, low: Set<String>) =
        HiddenArtists.filter(songs, hidden) { it.artist }.filter { low.isEmpty() || RadioMixer.artistKey(it.artist) !in low }

    /** Shows the cached shelves and refreshes them in the background once they are stale. */
    fun refresh(context: Context, repo: MusicRepository) {
        if (job?.isActive == true) return
        HiddenArtists.init(context)
        val ratings = Ratings.get(context)
        val f = File(context.applicationContext.cacheDir, "recommendations.json").also { file = it }
        job = scope.launch {
            if (!loaded) { loaded = true; runCatching { read(f) } }
            val now = System.currentTimeMillis()
            val print = Stars.fingerprint(ratings.allNow())
            val playedDue = now - savedAt >= TTL_MS
            val starsDue = now - starsAt >= STARS_TTL_MS || Stars.movedALot(starPrint, print)
            if ((!playedDue && !starsDue) || now - triedAt < RETRY_MS) return@launch
            // offline or no online source: keep whatever is cached
            val provider = SongRadio.provider.value ?: return@launch
            if (NetworkGate.offline.value) return@launch
            triedAt = now
            _building.value = true
            try {
                val played = repo.recentlyPlayed(100).first().mapTo(HashSet()) { it.id }
                val fresh = if (playedDue) build(provider, repo, played) else null
                val groups = if (starsDue) buildStars(provider, repo, ratings, played, now) else null
                if (fresh == null && groups == null) return@launch
                val next = _shelves.value.toMutableMap()
                fresh?.takeIf { m -> m.values.any { it.isNotEmpty() } }?.let { next.putAll(it); savedAt = now }
                groups?.takeIf { it.isNotEmpty() }?.let {
                    _stars.value = it
                    next[Shelf.LIKED] = Stars.flatten(it.map(StarGroup::songs))
                    lastSeeds = it.flatMapTo(HashSet()) { g -> g.seeds }
                    starsAt = now
                    starPrint = print
                }
                if (next == _shelves.value) return@launch
                val mixed = listOfNotNull(next[Shelf.PLAYED], next[Shelf.LIKED])
                next[Shelf.FOR_YOU] = RadioMixer.mix(mixed, played).take(KEEP)
                _shelves.value = next
                runCatching { write(f) }
            } finally {
                _building.value = false
            }
        }
    }

    /**
     * "Not for me" on a song of the stars shelf: drops it from every shelf for good and halves the weight
     * of the seeds that brought it.
     */
    fun notForMe(id: String) {
        scope.launch {
            synchronized(lock) {
                _stars.value.filter { g -> g.songs.any { it.id == id } }.flatMap { it.seeds }.forEach { penalties[it] = (penalties[it] ?: 0) + 1 }
                rejected += id
                while (rejected.size > MAX_REJECTED) rejected.remove(rejected.first())
            }
            _stars.value = _stars.value.map { g -> g.copy(songs = g.songs.filterNot { it.id == id }) }.filter { it.songs.isNotEmpty() }
            _shelves.value = _shelves.value.mapValues { (_, l) -> l.filterNot { it.id == id } }
            file?.let { runCatching { write(it) } }
        }
    }

    private suspend fun build(provider: SongProvider, repo: MusicRepository, played: Set<String>): Map<Shelf, List<Song>> {
        val recent = repo.recentlyPlayed(100).first()
        val most = repo.mostPlayed(20).first()
        return mapOf(Shelf.PLAYED to shelf(provider, (recent.take(2) + most.take(2)).distinctBy { it.id }, recent + most, played))
    }

    /** Song radios of a few starred seeds, grouped by theme and curated by [Stars.curate]. */
    private suspend fun buildStars(provider: SongProvider, repo: MusicRepository, ratings: Ratings, played: Set<String>, now: Long): List<StarGroup> {
        val rated = ratings.allNow()
        val library = repo.tracks.value
        val favorites = repo.favoriteTracks()
        val seeds = withLastFmThemes(Stars.seeds(rated, ratings.allTags(), library, favorites, synchronized(lock) { penalties.toMap() }, now))
        val picked = Stars.pick(seeds, lastSeeds, Random(now))
        if (picked.isEmpty()) return emptyList()
        val radios = picked.map { group -> Stars.flatten(group.flatMap { radio(provider, repo, it) }) }
        val ratedSongs = rated.filter { it.kind == RatingKind.SONG }.map { Song(it.key, it.title, it.artist) }
        val known = Stars.Known(library.map { it.toSong() } + favorites.map { it.toSong() } + ratedSongs +
            synchronized(lock) { rejected.toList() }.map { Song(it, "", "") })
        val hidden = HiddenArtists.names.value.mapTo(HashSet(), RadioMixer::artistKey)
        val curated = Stars.curate(radios, known, hidden, Ratings.lowArtists.value, played)
        return curated.mapIndexedNotNull { i, songs ->
            songs.takeIf { it.isNotEmpty() }?.let { StarGroup(Stars.label(picked[i]), picked[i].map { s -> s.key }, it) }
        }
    }

    /** Seeds without a personal tag share a theme by the artist's Last.fm genre, for the strongest few. */
    private suspend fun withLastFmThemes(seeds: List<StarSeed>): List<StarSeed> {
        if (!LastFm.enabled || NetworkGate.blockReason(Feature.LASTFM) != null) return seeds
        val top = seeds.filter { it.theme.startsWith("artist:") }.take(12).map { it.artist }.distinct()
        val tagOf = top.associateWith { a -> runCatching { LastFm.artistTopTag(a) }.getOrNull() }
        return seeds.map { s -> tagOf[s.artist]?.let { s.copy(theme = "tag:$it") } ?: s }
    }

    private suspend fun radio(provider: SongProvider, repo: MusicRepository, seed: StarSeed): List<List<Song>> {
        val track = seed.track ?: run {
            val title = seed.title.ifBlank {
                if (LastFm.enabled) runCatching { LastFm.artistTracks(seed.artist).firstOrNull()?.title }.getOrNull() ?: seed.artist else seed.artist
            }
            SongRadio.find(repo, seed.artist, title)
        } ?: return emptyList()
        return try {
            listOf(provider.radio(track).map { it.toSong() })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Mixes of the first [SEEDS] songs; [known] songs are left out. */
    private suspend fun shelf(provider: SongProvider, seeds: List<Track>, known: List<Track>, played: Set<String>): List<Song> {
        val mixes = ArrayList<List<Song>>()
        for (t in seeds) {
            if (mixes.size == SEEDS) break
            try {
                val mix = provider.radio(t).map { it.toSong() }
                if (mix.isNotEmpty()) mixes += mix
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // one failed mix only thins the shelf
            }
        }
        // local copies have other ids; sameWork still catches them by title and artist
        return RadioMixer.clean(known.map { it.toSong() }, RadioMixer.mix(mixes, played), played).take(KEEP)
    }

    private fun songs(a: JSONArray?): List<Song> = (0 until (a?.length() ?: 0)).map { i ->
        val j = a!!.getJSONObject(i)
        Song(j.getString("v"), j.getString("t"), j.getString("a"), durationMs = j.optLong("d"), thumbnail = j.optString("th").ifEmpty { null }, uri = j.optString("u"))
    }

    private fun json(list: List<Song>) =
        JSONArray(list.map { JSONObject().put("v", it.id).put("t", it.title).put("a", it.artist).put("d", it.durationMs).put("th", it.thumbnail ?: "").put("u", it.uri) })

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun read(file: File) {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        savedAt = o.optLong("savedAt")
        starsAt = o.optLong("starsAt")
        val shelves = o.getJSONObject("shelves")
        _shelves.value = Shelf.entries.associateWith { s -> songs(shelves.optJSONArray(s.name)) }
        val groups = o.optJSONArray("stars")
        _stars.value = (0 until (groups?.length() ?: 0)).map { i ->
            val g = groups!!.getJSONObject(i)
            StarGroup(g.getString("label"), strings(g.optJSONArray("seeds")), songs(g.optJSONArray("songs")))
        }
        lastSeeds = strings(o.optJSONArray("lastSeeds")).toSet()
        starPrint = o.optJSONArray("starPrint")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getInt(it) } }.orEmpty()
        synchronized(lock) {
            o.optJSONObject("penalties")?.let { p -> p.keys().forEach { penalties[it] = p.optInt(it) } }
            rejected += strings(o.optJSONArray("rejected"))
        }
    }

    private fun write(file: File) {
        val shelves = JSONObject()
        _shelves.value.forEach { (s, list) -> shelves.put(s.name, json(list)) }
        val stars = JSONArray(_stars.value.map { g -> JSONObject().put("label", g.label).put("seeds", JSONArray(g.seeds)).put("songs", json(g.songs)) })
        val (pen, rej) = synchronized(lock) { penalties.toMap() to rejected.toList() }
        file.writeText(
            JSONObject().put("savedAt", savedAt).put("starsAt", starsAt).put("shelves", shelves).put("stars", stars)
                .put("lastSeeds", JSONArray(lastSeeds.toList())).put("starPrint", JSONArray(starPrint.toList()))
                .put("penalties", JSONObject(pen)).put("rejected", JSONArray(rej))
                .toString()
        )
    }
}
