package com.slowva.player.ui.home

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.AlbumArt
import com.slowva.player.ui.CoverImage
import com.slowva.player.ui.SectionHeader
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.pressFeedback
import com.slowva.player.ui.skins.Skin
import com.slowva.player.ui.songs.SongActions
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws one widget section; Home and Library share it. */
@Composable
internal fun BoardWidget(
    s: HomeSection, repo: MusicRepository, tracks: List<Track>, liked: List<Track>, playlists: Int,
    onOpenPlayer: () -> Unit, onSpinWheel: () -> Unit,
) {
    when (s) {
        HomeSection.NOW_PLAYING -> NowPlayingWidget(repo, liked, onOpenPlayer)
        HomeSection.VINYL -> SkinWidget(Skin.VINYL, onOpenPlayer)
        HomeSection.CD -> SkinWidget(Skin.CD, onOpenPlayer)
        HomeSection.TAPE -> SkinWidget(Skin.TAPE, onOpenPlayer)
        HomeSection.TP7 -> SkinWidget(Skin.TP7, onOpenPlayer)
        HomeSection.RADIO -> RadioWidget(onOpenPlayer)
        HomeSection.VISUALIZER -> VisualizerWidget(onOpenPlayer)
        HomeSection.SHORTCUTS -> ShortcutsWidget(repo, liked, tracks, onSpinWheel)
        HomeSection.STATS -> StatsWidget(tracks, liked.size, playlists)
        HomeSection.CLOCK -> ClockWidget()
        else -> {}
    }
}

/** Bars that follow the music; flat and still while nothing plays. */
@Composable
internal fun VisualizerWidget(onOpen: () -> Unit) {
    val bands by PlayerHub.spectrum.collectAsStateWithLifecycle()
    val state by PlayerHub.playback.collectAsStateWithLifecycle()
    val playing = state?.isPlaying == true
    val c = MaterialTheme.colorScheme
    val top = Accent
    val bottom = c.tertiary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(c.surfaceContainer)
            .clickable(onClickLabel = "open the player", onClick = onOpen)
            .padding(16.dp),
    ) {
        Text(state?.track?.title ?: "Play something to see it move", color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state?.track?.artist?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Music visualizer" }) {
            val n = bands.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = size.height)
            for (i in 0 until n) {
                val v = if (playing) bands.getOrElse(i) { 0f }.coerceIn(0f, 1f) else 0f
                val h = (size.height * (0.04f + 0.96f * v))
                val x = i * (w + gap)
                // mirrored: a tall bar up, a faint reflection down
                drawRoundRect(brush, Offset(x, size.height * 0.7f - h * 0.7f), Size(w, h * 0.7f), CornerRadius(w / 2))
                drawRoundRect(top.copy(alpha = 0.25f), Offset(x, size.height * 0.72f), Size(w, h * 0.25f), CornerRadius(w / 2))
            }
        }
    }
}

/** Four big round buttons for the things you start most. */
@Composable
internal fun ShortcutsWidget(repo: MusicRepository, liked: List<Track>, all: List<Track>, onSpinWheel: () -> Unit) {
    val most by remember(repo) { repo.mostPlayed(50) }.collectAsStateWithLifecycle(initialValue = emptyList())
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Shortcut(Icons.Filled.Favorite, "Liked mix", MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f), enabled = liked.isNotEmpty()) { PlayList.shuffle(repo, liked) }
        Shortcut(Icons.Filled.Shuffle, "Shuffle all", MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f), enabled = all.isNotEmpty()) { PlayList.shuffle(repo, all) }
        Shortcut(Icons.Filled.BarChart, "Most played", MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f), enabled = most.isNotEmpty()) { PlayList.shuffle(repo, most) }
        Shortcut(Icons.Filled.Casino, "Spin", MaterialTheme.colorScheme.surfaceContainerHighest, Modifier.weight(1f), onClick = onSpinWheel)
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, color: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressFeedback(press)
            .clip(MaterialTheme.shapes.large)
            .background(color.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(press, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Songs, liked, playlists and artists as four big numbers. */
@Composable
internal fun StatsWidget(tracks: List<Track>, liked: Int, playlists: Int) {
    val artists = remember(tracks) { tracks.map { it.artist.lowercase() }.filter { it.isNotBlank() && it != "unknown artist" }.distinct().size }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        SectionHeader("Your music in numbers", Modifier.padding(top = 8.dp, start = 0.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${tracks.size}", "songs", MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f))
            Stat("$liked", "liked", MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("$playlists", "playlists", MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f))
            Stat("$artists", "artists", MaterialTheme.colorScheme.surfaceContainerHighest, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(color).padding(16.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
    }
}

/** The time in big type, the date and what's playing under it. */
@Composable
internal fun ClockWidget() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val state by PlayerHub.playback.collectAsStateWithLifecycle()
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainer)))
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Column {
            Text(time, color = TextPrimary, fontSize = 64.sp, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge)
            Text(date.replaceFirstChar { it.uppercase() }, color = TextSecondary, style = MaterialTheme.typography.titleMedium)
            state?.track?.let { t ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state?.isPlaying == true) Icons.Filled.PlayArrow else Icons.Filled.Bedtime, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${t.title} · ${t.artist}", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
