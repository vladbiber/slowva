package com.slowva.player.api

data class Track(
    val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Int,
    val art: String?,
)

data class Album(
    val id: String,
    val uri: String,
    val name: String,
    val artist: String,
    val art: String?,
)

data class Playlist(
    val id: String,
    val uri: String,
    val name: String,
    val owner: String,
    val art: String?,
)

data class Device(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val volumePercent: Int,
)

data class PlaybackState(
    val track: Track?,
    val isPlaying: Boolean,
    val progressMs: Int,
    val deviceId: String?,
    val deviceName: String?,
    val shuffle: Boolean,
    val repeat: String,
    val volumePercent: Int,
)

/** A named source of tracks in the library screen. */
sealed class Source(val title: String) {
    object Liked : Source("Liked songs")
    data class OfPlaylist(val playlist: Playlist) : Source(playlist.name)
    data class OfAlbum(val album: Album) : Source(album.name)
    data class SearchResults(val query: String) : Source("Search")
}
