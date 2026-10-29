package com.slowva.player.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.spotify.SpCollection
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyLibrary

/** Media ids of the Android Auto browse tree: three tabs, playlists one level down, playable leaves "list<SEP>id". */
object AutoIds {
    const val ROOT = "auto.root"
    const val FAVORITES = "auto.favorites"
    const val PLAYLISTS = "auto.playlists"
    const val SONGS = "auto.songs"
    val TABS = listOf(FAVORITES, PLAYLISTS, SONGS)
    const val SP_LIKED = "auto.sp.liked"
    const val SP_PLAYLISTS = "auto.sp.playlists"
    /** shown after [TABS] while Spotify is signed in */
    val SPOTIFY_TABS = listOf(SP_LIKED, SP_PLAYLISTS)
    private const val PLAYLIST = "auto.playlist:"
    private const val SP_PLAYLIST = "auto.sp.playlist:"
    // unit separator: never typed into a playlist name nor part of a track id
    private const val SEP = '\u001F'

    fun playlist(name: String) = PLAYLIST + name
    fun playlistName(id: String): String? = if (id.startsWith(PLAYLIST)) id.removePrefix(PLAYLIST) else null
    fun spPlaylist(spotifyId: String) = SP_PLAYLIST + spotifyId
    fun spPlaylistId(id: String): String? = if (id.startsWith(SP_PLAYLIST)) id.removePrefix(SP_PLAYLIST) else null
    fun leaf(list: String, itemId: String) = "$list$SEP$itemId"

    /** (list id, track id) of a playable leaf; null for folders and any other id. */
    fun parseLeaf(id: String): Pair<String, String>? {
        val i = id.indexOf(SEP)
        if (i <= 0 || i == id.lastIndex) return null
        val list = id.substring(0, i)
        return if (list in TABS || list == SP_LIKED || playlistName(list) != null || spPlaylistId(list) != null) list to id.substring(i + 1) else null
    }

    fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
        if (page < 0 || size <= 0) return emptyList()
        val from = page.toLong() * size
        if (from >= items.size) return emptyList()
        return items.subList(from.toInt(), minOf(items.size.toLong(), from + size).toInt())
    }
}

/** Builds the Auto browse tree from the library, and turns a tapped leaf into a [Cmd]. */
class AutoLibrary(private val repository: MusicRepository) {

    val root: MediaItem = folder(AutoIds.ROOT, "Slowva", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem>? = when (parentId) {
        AutoIds.ROOT -> (AutoIds.TABS + if (Spotify.signedIn.value) AutoIds.SPOTIFY_TABS else emptyList()).map(::tab)
        AutoIds.PLAYLISTS -> { ensureLoaded(); repository.playlists.value.map { playlistFolder(it.name, it.coverUri) } }
        AutoIds.SP_PLAYLISTS -> {
            SpotifyLibrary.load()
            SpotifyLibrary.state.value.playlists.map { folder(AutoIds.spPlaylist(it.id), it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, it.art) }
        }
        else -> tracks(parentId)?.map { song(parentId, it) }
    }

    suspend fun item(id: String): MediaItem? {
        if (id == AutoIds.ROOT) return root
        if (id in AutoIds.TABS || id in AutoIds.SPOTIFY_TABS) return tab(id)
        AutoIds.spPlaylistId(id)?.let { sp ->
            return SpotifyLibrary.state.value.playlists.firstOrNull { it.id == sp }?.let { folder(id, it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, it.art) }
        }
        AutoIds.playlistName(id)?.let { name ->
            ensureLoaded()
            return repository.playlists.value.firstOrNull { it.name == name }?.let { playlistFolder(it.name, it.coverUri) }
        }
        val (list, itemId) = AutoIds.parseLeaf(id) ?: return null
        ensureLoaded()
        return repository.trackById(itemId)?.let { song(list, it) }
    }

    /** The custom command the app itself sends for this leaf: its list becomes the queue. */
    suspend fun playCommand(id: String): Pair<String, Bundle>? {
        val (list, itemId) = AutoIds.parseLeaf(id) ?: return null
        val tracks = tracks(list) ?: return null
        val index = tracks.indexOfFirst { it.id == itemId }.takeIf { it >= 0 } ?: return null
        return Cmd.PLAY_QUEUE to Bundle().apply {
            putStringArray(Cmd.ARG_IDS, tracks.map { it.id }.toTypedArray()); putInt(Cmd.ARG_INDEX, index)
        }
    }

    private suspend fun tracks(list: String): List<Track>? {
        ensureLoaded()
        return when (list) {
            AutoIds.FAVORITES -> repository.favoriteTracks()
            AutoIds.SONGS -> repository.tracks.value
            AutoIds.SP_LIKED -> spotify(SpCollection.LIKED)
            else -> AutoIds.playlistName(list)?.let(repository::playlistTracks)
                ?: AutoIds.spPlaylistId(list)?.let { spotify(SpCollection(SpCollection.Kind.PLAYLIST, it, "", "", null)) }
        }
    }

    /** A Spotify list, remembered so its songs resolve by id when queued; empty when Spotify can't be reached. */
    private suspend fun spotify(c: SpCollection): List<Track> {
        if (!Spotify.signedIn.value) return emptyList()
        return try {
            SpotifyLibrary.tracks(c).also(repository::remember)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // shortcut: Auto can connect before the service's own load finishes; a second load waits on the lock and reloads once
    private suspend fun ensureLoaded() {
        if (repository.tracks.value.isEmpty()) repository.load(includeDevice = false)
    }

    private fun tab(id: String) = when (id) {
        AutoIds.FAVORITES -> folder(id, "Favorites", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        AutoIds.PLAYLISTS -> folder(id, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        AutoIds.SP_LIKED -> folder(id, "Spotify liked", MediaMetadata.MEDIA_TYPE_PLAYLIST)
        AutoIds.SP_PLAYLISTS -> folder(id, "Spotify playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        else -> folder(id, "All songs", MediaMetadata.MEDIA_TYPE_PLAYLIST)
    }

    private fun playlistFolder(name: String, cover: String?) = folder(
        AutoIds.playlist(name), name, MediaMetadata.MEDIA_TYPE_PLAYLIST,
        cover ?: repository.playlistTracks(name).firstNotNullOfOrNull { it.coverUri },
    )

    private fun song(list: String, t: Track) = playable(
        AutoIds.leaf(list, t.id), t.title, t.artist, t.coverUri, MediaMetadata.MEDIA_TYPE_MUSIC,
    )

    private fun folder(id: String, title: String, type: Int, art: String? = null) =
        mediaItem(id, MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(type), art)

    private fun playable(id: String, title: String, subtitle: String, art: String?, type: Int) = mediaItem(
        id, MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setIsBrowsable(false).setIsPlayable(true).setMediaType(type), art,
    )

    private fun mediaItem(id: String, meta: MediaMetadata.Builder, art: String?) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(meta.setArtworkUri(art?.let(Uri::parse)).build())
        .build()
}
