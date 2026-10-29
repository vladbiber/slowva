package com.slowva.player.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.slowva.player.BuildConfig
import com.slowva.player.keys.KeyRouter
import com.slowva.player.listenbrainz.ListenBrainz
import com.slowva.player.net.NetworkGate
import com.slowva.player.ui.rating.RymImportRow
import com.slowva.player.ui.settings.AboutSettings
import com.slowva.player.ui.settings.BackupSettings
import com.slowva.player.ui.settings.ConnectionsSection
import com.slowva.player.ui.settings.HistorySettings
import com.slowva.player.ui.settings.KeyboardSettingsScreen
import com.slowva.player.ui.settings.LookSettings
import com.slowva.player.ui.settings.MenuRow
import com.slowva.player.ui.settings.NetworkSettings
import com.slowva.player.ui.settings.PlaybackSettings
import com.slowva.player.ui.settings.SettingsPage
import com.slowva.player.ui.settings.SpotifySection
import com.slowva.player.spotify.Spotify
import com.slowva.player.ui.theme.Appearance

private enum class SettingsSection(val title: String, val icon: ImageVector) {
    LOOK("Look", Icons.Filled.Palette),
    PLAYBACK("Playback & sound", Icons.Filled.GraphicEq),
    NETWORK("Network & data", Icons.Filled.Wifi),
    CONNECTIONS("Connections", Icons.Filled.Link),
    HISTORY("History", Icons.Filled.History),
    KEYBOARD("Keyboard", Icons.Filled.Keyboard),
    BACKUP("Backup", Icons.Filled.Backup),
    ABOUT("About", Icons.Filled.Info),
}

/** A short menu of pages; each opens with a back arrow and system Back returns here. */
@Composable
fun SettingsScreen(onClose: (() -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val back = { open = null }
    when (open) {
        null -> SettingsMenu(onClose) { open = it }
        SettingsSection.KEYBOARD -> KeyboardSettingsScreen(onBack = back)
        SettingsSection.LOOK -> SettingsPage("Look", back) { LookSettings() }
        SettingsSection.PLAYBACK -> SettingsPage("Playback & sound", back) { PlaybackSettings() }
        SettingsSection.NETWORK -> SettingsPage("Network & data", back) { NetworkSettings() }
        SettingsSection.CONNECTIONS -> SettingsPage("Connections", back) {
            SpotifySection()
            SectionHeader("ListenBrainz", Modifier.padding(top = 24.dp, bottom = 4.dp))
            ConnectionsSection()
            SectionHeader("RateYourMusic", Modifier.padding(top = 24.dp, bottom = 4.dp))
            RymImportRow()
        }
        SettingsSection.HISTORY -> SettingsPage("History", back) { HistorySettings() }
        SettingsSection.BACKUP -> SettingsPage("Backup", back) { BackupSettings() }
        SettingsSection.ABOUT -> SettingsPage("About", back) { AboutSettings() }
    }
}

@Composable
private fun SettingsMenu(onClose: (() -> Unit)?, onOpen: (SettingsSection) -> Unit) {
    val offline by NetworkGate.offline.collectAsState()
    val keys by KeyRouter.enabled.collectAsState()
    val lb by ListenBrainz.state.collectAsState()
    val spotify by Spotify.signedIn.collectAsState()
    val gradient by Appearance.gradient.collectAsState()
    val font by Appearance.font.collectAsState()
    SettingsPage("Settings", onClose) {
        SettingsSection.entries.forEachIndexed { i, s ->
            val summary = when (s) {
                SettingsSection.LOOK -> "Colors, ${gradient.label.lowercase()} background, ${font.label}"
                SettingsSection.PLAYBACK -> "Headphones, hi-fi, EQ, hidden artists"
                SettingsSection.NETWORK -> if (offline) "Offline mode is on" else "Services and Wi-Fi"
                SettingsSection.CONNECTIONS -> (if (spotify) "Spotify signed in · " else "Spotify, ") + (lb.user?.let { "ListenBrainz: $it · RYM import" } ?: "ListenBrainz, RYM import")
                SettingsSection.HISTORY -> "Home rows, what gets remembered, clear"
                SettingsSection.KEYBOARD -> if (keys) "Shortcuts on, keys and screen shape" else "Shortcuts off, keys and screen shape"
                SettingsSection.BACKUP -> "Save or restore everything in one file"
                SettingsSection.ABOUT -> "Slowva ${BuildConfig.VERSION_NAME}, license, credits"
            }
            MenuRow(s.icon, s.title, summary, Modifier.enterStagger(i)) { onOpen(s) }
        }
    }
}
