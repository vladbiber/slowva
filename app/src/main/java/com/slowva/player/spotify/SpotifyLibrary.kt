package com.slowva.player.spotify

import com.slowva.player.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** A Spotify list that opens as a page: Liked songs, a playlist or an album. */
data class SpCollection(val kind: Kind, val id: String, val title: String, val subtitle: String, val art: String?) : java.io.Serializable {
    enum class Kind { LIKED, PLAYLIST, ALBUM, ARTIST }

    companion object {
        val LIKED = SpCollection(Kind.LIKED, "liked", "Liked songs", "Spotify", null)
        fun of(p: SpPlaylist) = SpCollection(Kind.PLAYLIST, p.id, p.name, p.owner.ifBlank { "Playlist" }, p.art)
        fun of(a: SpAlbum) = SpCollection(Kind.ALBUM, a.id, a.name, a.artist, a.art)
        fun of(a: SpArtist) = SpCollection(Kind.ARTIST, a.id, a.name, "Artist", a.art)
    }
}

/** Your Spotify library, loaded once per session and on demand. */
object SpotifyLibrary {
    data class State(
        val liked: List<Track> = emptyList(),
        val playlists: List<SpPlaylist> = emptyList(),
        val albums: List<SpAlbum> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()
    private val tracks = ConcurrentHashMap<String, List<Track>>()

    /** Loads the library unless it already is; [force] reloads. */
    suspend fun load(force: Boolean = false) = lock.withLock {
        if (!Spotify.signedIn.value) return@withLock
        if (_state.value.loaded && !force) return@withLock
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val liked = SpotifyApi.liked().map { it.toTrack() }
            val playlists = SpotifyApi.playlists()
            val albums = SpotifyApi.albums()
            _state.value = State(liked, playlists, albums, loaded = true)
            tracks[key(SpCollection.LIKED)] = liked
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(loading = false)
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Couldn't load your Spotify library")
        }
    }

    /** The songs of [c], cached for the session. */
    suspend fun tracks(c: SpCollection, force: Boolean = false): List<Track> {
        val key = key(c)
        if (!force) tracks[key]?.let { return it }
        val list = when (c.kind) {
            SpCollection.Kind.LIKED -> SpotifyApi.liked().map { it.toTrack() }
            SpCollection.Kind.PLAYLIST -> SpotifyApi.playlistTracks(c.id).map { it.toTrack() }
            SpCollection.Kind.ALBUM -> SpotifyApi.albumTracks(SpAlbum(c.id, c.title, c.subtitle, c.art)).map { it.toTrack() }
            // top tracks may be closed to some clients; a search by artist is the fallback
            SpCollection.Kind.ARTIST -> runCatching { SpotifyApi.artistTopTracks(c.id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.map { it.toTrack() }
                ?: SpotifyApi.searchTracks("artist:${SpotifyMatch.quoted(c.title)}", 10).map { it.toTrack() }
        }
        tracks[key] = list
        return list
    }

    private fun key(c: SpCollection) = "${c.kind}:${c.id}"

    fun clear() {
        tracks.clear()
        _state.value = State()
    }
}
