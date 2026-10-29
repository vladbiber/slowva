package com.slowva.player.data

/** A single playable track: a demo asset, a device or imported file, or an online song (see [isOnline]). */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val coverUri: String?,
    val durationMs: Long,
    val isDemo: Boolean,
    val isImported: Boolean = false,
) {
    /** Played over the network; its [uri] scheme is routed by RoutingDataSourceFactory. */
    val isOnline: Boolean get() = isOnlineUri(uri)

    companion object {
        private val LOCAL_SCHEMES = setOf("", "file", "content", "android.resource")

        fun isOnlineUri(uri: String): Boolean = uri.substringBefore(':', "") !in LOCAL_SCHEMES
    }
}

/** A user playlist: an ordered list of track ids, with an optional custom cover image. */
data class Playlist(
    val name: String,
    val trackIds: List<String>,
    val coverUri: String? = null,
)

/** Persisted playback snapshot so the app reopens where the user left off. */
data class PlaybackState(
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val volume: Float = 1f,
)
