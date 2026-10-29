package com.slowva.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slowva.player.data.Track
import com.slowva.player.musicbrainz.MbGroup
import com.slowva.player.musicbrainz.MbParse
import com.slowva.player.musicbrainz.MbTrack
import com.slowva.player.musicbrainz.MusicBrainz
import com.slowva.player.net.Feature
import com.slowva.player.net.NetworkGate
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** True while MusicBrainz lookups are allowed: not offline and not switched off. */
@Composable
internal fun rememberMbOn(): Boolean {
    val offline by NetworkGate.offline.collectAsStateWithLifecycle()
    val features by NetworkGate.enabled.collectAsStateWithLifecycle()
    return !offline && features[Feature.MUSICBRAINZ] != false
}

/** "1997 · Album · Live" */
internal fun MbGroup.label(): String =
    listOfNotNull(year?.toString(), (listOfNotNull(primaryType) + secondaryTypes).joinToString(" · ").ifEmpty { null }).joinToString(" · ")

/** The artist's studio albums and EPs from MusicBrainz, oldest first; nothing when off or unknown. */
@Composable
internal fun DiscographyRow(artist: String, onOpen: (HomePage) -> Unit) {
    val on = rememberMbOn()
    val groups = if (on) rememberLoad(artist, emptyList()) { MusicBrainz.discography(artist) } else emptyList()
    if (groups.isEmpty()) return
    Column {
        SectionHeader("Discography", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups, key = { it.id }) { g ->
                Column(
                    Modifier.width(120.dp).clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = "open album") { onOpen(HomePage.Album(g.title, artist)) }
                        .semantics(mergeDescendants = true) { contentDescription = "${g.title}, ${g.label()}" },
                ) {
                    Cover(g.coverUrl, 120.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(g.title, color = TextPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.label(), color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A Cover Art Archive picture on a tinted square, so a missing cover still looks placed. */
@Composable
internal fun Cover(url: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondaryContainer)) {
        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Small genre chips from MusicBrainz. */
@Composable
internal fun TagChips(tags: List<String>) {
    if (tags.isEmpty()) return
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(genreLabel(t), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** One MusicBrainz track: yours plays from the library, a missing one from the online source. */
@Composable
internal fun MbTrackRow(number: String, t: MbTrack, mine: Track?, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "play", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number, color = TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (mine != null) TextPrimary else TextSecondary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (mine != null) "In your library" else "Play online", color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(MbParse.lengthLabel(t.lengthMs), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                mine != null -> Icon(Icons.Filled.CheckCircle, "in your library", tint = Accent, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Outlined.PlayCircleOutline, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}
