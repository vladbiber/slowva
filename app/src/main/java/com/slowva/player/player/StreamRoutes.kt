package com.slowva.player.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.slowva.player.spotify.Spotify
import com.slowva.player.spotify.SpotifyIds

/** Uri schemes of online songs and the source that plays each; read once when the player is built. */
@UnstableApi
object StreamRoutes {
    val all: Map<String, () -> DataSource> = mapOf(SpotifyIds.SCHEME to { SpotifyDataSource(Spotify.app) })
}
