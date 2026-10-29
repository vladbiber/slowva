package com.slowva.player.spotify

import com.slowva.player.data.Track
import com.slowva.player.lastfm.LastFm
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import com.slowva.player.recommend.RadioMixer
import com.slowva.player.recommend.SongProvider
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Picks the Spotify song that is really the one asked for; pure, so it is unit tested. */
object SpotifyMatch {
    private const val DURATION_SLACK_MS = 15_000L

    fun best(candidates: List<SpTrack>, artist: String?, title: String, durationMs: Long = 0L): SpTrack? {
        val work = RadioMixer.work(title).ifEmpty { return null }
        val key = artist?.takeIf { it.isNotBlank() }?.let(RadioMixer::artistKey)
        val wantsVersion = RadioMixer.isVersion(title)
        return candidates.asSequence()
            .filter { it.playable && RadioMixer.work(it.name) == work }
            .filter { key == null || it.artists.any { a -> RadioMixer.artistKey(a.name) == key } }
            .filter { durationMs <= 0 || it.durationMs <= 0 || abs(it.durationMs - durationMs) <= DURATION_SLACK_MS }
            // the studio cut unless a live or remix was asked for, then the closest length
            .sortedWith(compareBy<SpTrack> { if (RadioMixer.isVersion(it.name) == wantsVersion) 0 else 1 }.thenBy { if (durationMs > 0) abs(it.durationMs - durationMs) else 0L })
            .firstOrNull()
    }

    /** One song from each list in turn, without repeats or [skip]. */
    fun interleave(lists: List<List<SpTrack>>, skip: Set<String>): List<SpTrack> {
        val seen = HashSet(skip)
        val out = ArrayList<SpTrack>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.takeIf { it.playable && seen.add(it.id) }?.let(out::add) }
        return out
    }

    /** A search filter value in quotes, without quotes of its own. */
    fun quoted(s: String) = "\"" + s.replace("\"", " ").trim() + "\""
}

/**
 * The online catalog for recommendations, Up next and song radio.
 * Spotify's /recommendations is closed to new apps, so a radio is the seed artist's popular songs
 * mixed with those of similar artists (Last.fm) or of the artist's genre.
 */
object SpotifyProvider : SongProvider {
    private val radios = ConcurrentHashMap<String, List<Track>>()

    override suspend fun find(artist: String?, title: String, durationMs: Long): Track? {
        val q = if (artist.isNullOrBlank()) title else "track:${SpotifyMatch.quoted(title)} artist:${SpotifyMatch.quoted(RadioMixer.leadArtist(artist))}"
        val hits = SpotifyApi.searchTracks(q, 10)
        return (SpotifyMatch.best(hits, artist, title, durationMs)
            // a looser search catches titles the field filter misses
            ?: if (artist.isNullOrBlank()) null else SpotifyMatch.best(SpotifyApi.searchTracks("$title $artist", 10), artist, title, durationMs))?.toTrack()
    }

    override suspend fun radio(seed: Track): List<Track> {
        radios[seed.id]?.let { return it }
        val lead = RadioMixer.leadArtist(seed.artist).ifBlank { return emptyList() }
        val lists = ArrayList<List<SpTrack>>()
        lists += SpotifyApi.searchTracks("artist:${SpotifyMatch.quoted(lead)}", 10)
        val similar = similarArtists(lead)
        if (similar.isNotEmpty()) {
            similar.take(4).forEach { a -> lists += safely { SpotifyApi.searchTracks("artist:${SpotifyMatch.quoted(a)}", 6) } }
        } else {
            val genre = safely { SpotifyApi.search("artist:${SpotifyMatch.quoted(lead)}", 1).artists }.firstOrNull()?.genres?.firstOrNull()
            if (genre != null) lists += safely { SpotifyApi.searchTracks("genre:${SpotifyMatch.quoted(genre)}", 10) }
        }
        val skip = setOfNotNull(SpotifyIds.spotifyId(seed.id) ?: SpotifyIds.spotifyId(seed.uri))
        val mix = SpotifyMatch.interleave(lists, skip).map { it.toTrack() }
        if (mix.isNotEmpty()) radios[seed.id] = mix
        return mix
    }

    private suspend fun similarArtists(artist: String): List<String> {
        if (!LastFm.enabled || NetworkGate.blockReason(Feature.LASTFM) != null) return emptyList()
        return safely { LastFm.similar(artist) }.map { it.name }
    }

    private suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
