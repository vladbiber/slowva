package com.slowva.player.ui.wheel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slowva.player.data.Playlist
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceDark
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** What the "Spin from" button reads for the chosen source. */
internal fun sourceLabel(source: WheelSource?, scope: LibraryScope): String = when {
    source == null -> "Nothing"
    source !is LibrarySource -> source.label
    scope == LibraryScope.All -> source.label
    scope == LibraryScope.Favorites -> "Liked songs"
    else -> (scope as LibraryScope.InPlaylist).name
}

/** "Spin from: Your library ▾" plus the options button; the sheet lists every source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WheelSourceBar(
    all: List<WheelSource>,
    selectedId: String?,
    libScope: LibraryScope,
    playlists: List<Playlist>,
    mode: WheelMode,
    enabled: Boolean,
    onPick: (id: String, scope: LibraryScope?) -> Unit,
    onMode: (WheelMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val current = all.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            FilledTonalButton(onClick = { open = true }, enabled = enabled) {
                Text("Spin from: ", maxLines = 1)
                Text(sourceLabel(current, libScope), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        Box {
            IconButton(onClick = { options = true }, enabled = enabled) {
                Icon(Icons.Filled.Tune, contentDescription = "Wheel options, ${mode.label}", tint = TextSecondary)
            }
            DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                Text("How it picks", color = TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                WheelMode.entries.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        trailingIcon = { if (m == mode) Icon(Icons.Filled.Check, null, tint = Accent) },
                        onClick = { onMode(m); options = false },
                    )
                }
            }
        }
    }

    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = SurfaceDark) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp).selectableGroup()) {
            Text(
                "Spin from", color = TextPrimary, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            all.forEach { s ->
                if (s is LibrarySource) {
                    val pick = { sc: LibraryScope -> onPick(s.id, sc); open = false }
                    SourceRow(Icons.Filled.LibraryMusic, s.label, "Every song you have", s.id == selectedId && libScope == LibraryScope.All) { pick(LibraryScope.All) }
                    SourceRow(Icons.Filled.Favorite, "Liked songs", null, s.id == selectedId && libScope == LibraryScope.Favorites) { pick(LibraryScope.Favorites) }
                    playlists.forEach { pl ->
                        val sc = LibraryScope.InPlaylist(pl.name)
                        SourceRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name, "Playlist", s.id == selectedId && libScope == sc) { pick(sc) }
                    }
                } else {
                    SourceRow(
                        Icons.Filled.Shuffle,
                        s.label, s.unavailable, s.id == selectedId, enabled = s.unavailable == null,
                    ) { onPick(s.id, null); open = false }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(icon: ImageVector, label: String, sub: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Accent else TextSecondary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) Accent else TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
    }
}
