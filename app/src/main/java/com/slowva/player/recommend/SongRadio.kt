package com.slowva.player.recommend

import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Plays a song known only by name (Last.fm, MusicBrainz): the library copy first, else the online [provider]. */
object SongRadio {
    /** set by an online source; recommendations and Up next stay hidden without it */
    val provider = MutableStateFlow<SongProvider?>(null)

    // per session; an empty list means no confident match
    private val found = ConcurrentHashMap<String, List<Track>>()

    /** The song for [artist] and [title]: a library song with the same work and artist, else a confident online match. */
    suspend fun find(repo: MusicRepository, artist: String?, title: String, durationMs: Long = 0L): Track? {
        local(repo.tracks.value, artist, title)?.let { return it }
        val p = provider.value ?: return null
        val key = "${artist.orEmpty().lowercase()}|${title.lowercase()}"
        found[key]?.let { return it.firstOrNull() }
        val song = try {
            p.find(artist, title, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        found[key] = listOfNotNull(song)
        return song
    }

    /** What to say when [find] gives null. */
    fun missing(): String = if (provider.value == null) "Not in your library" else "Not found"

    /** A library song matching [title] by [artist] (any artist when null). */
    fun local(library: List<Track>, artist: String?, title: String): Track? {
        val work = RadioMixer.work(title).ifEmpty { return null }
        val key = artist?.let(RadioMixer::artistKey)
        return library.firstOrNull { RadioMixer.work(it.title) == work && (key == null || RadioMixer.artistKey(it.artist) == key) }
    }

    /** Starts [first] at once, then queues its cleaned song radio behind it when there is a [provider]. */
    suspend fun play(repo: MusicRepository, first: Track) {
        repo.remember(listOf(first))
        PlayerHub.playQueue(listOf(first.id), 0)
        val p = provider.value ?: return
        val mix = try {
            p.radio(first).map { it.toSong() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val rest = HiddenArtists.filter(RadioMixer.clean(listOf(first.toSong()), mix, emptySet()), HiddenArtists.names.value) { it.artist }.take(25).map { it.toTrack() }
        repo.remember(rest)
        rest.forEach { PlayerHub.addToQueue(it.id) }
    }
}
