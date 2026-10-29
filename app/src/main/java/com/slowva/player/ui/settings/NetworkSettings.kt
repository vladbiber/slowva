package com.slowva.player.ui.settings

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import com.slowva.player.player.StreamRoutes
import com.slowva.player.ui.SectionHeader

/** Offline mode, each online service and the Wi-Fi rule for streaming. */
@OptIn(UnstableApi::class)
@Composable
internal fun ColumnScope.NetworkSettings() {
    val offline by NetworkGate.offline.collectAsState()
    val features by NetworkGate.enabled.collectAsState()
    val wifiOnlyStreaming by NetworkGate.wifiOnlyStreaming.collectAsState()
    val streamingOn = features[Feature.STREAMING] != false

    SwitchRow("Offline mode", "Block all internet use. Your music keeps working.", offline, NetworkGate::setOffline)

    SectionHeader("Services", Modifier.padding(top = 16.dp, bottom = 4.dp))
    // nothing streams until an online source registers its scheme
    if (StreamRoutes.all.isNotEmpty()) {
        SwitchRow(Feature.STREAMING.label, "Search and play songs that aren't on the phone", streamingOn, { NetworkGate.setEnabled(Feature.STREAMING, it) }, enabled = !offline)
        SwitchRow(
            "Streaming on mobile data", "Off means online songs only play on Wi-Fi",
            !wifiOnlyStreaming, { NetworkGate.setWifiOnlyStreaming(!it) }, enabled = !offline && streamingOn,
        )
    }
    SwitchRow("Genres and creators (Last.fm)", "Genre and artist suggestions on Home", features[Feature.LASTFM] != false, { NetworkGate.setEnabled(Feature.LASTFM, it) }, enabled = !offline)
    SwitchRow("Lyrics (LRCLIB)", "Synced lyrics in the player", features[Feature.LYRICS] != false, { NetworkGate.setEnabled(Feature.LYRICS, it) }, enabled = !offline)
    SwitchRow("Album info (MusicBrainz)", "Track lists, years, community ratings and discographies", features[Feature.MUSICBRAINZ] != false, { NetworkGate.setEnabled(Feature.MUSICBRAINZ, it) }, enabled = !offline)
}
