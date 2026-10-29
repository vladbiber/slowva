package com.slowva.player.ui.songs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.AddToPlaylistSheet
import com.slowva.player.ui.TrackRow
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** A song row: cover, title, artist and ⋮ ([SongSheet] holds Like and Add to playlist); long press = select. */
@Composable
fun SongRow(
    track: Track,
    context: SongContext,
    pool: List<Track>,
    onPlay: () -> Unit,
    status: Pair<String, ImageVector>? = null,
) {
    val now by PlayerHub.nowPlaying.collectAsStateWithLifecycle()
    val sel by Selecting.state.collectAsStateWithLifecycle()
    val selecting = sel.isSelecting(context)
    val on = track.id == now.first
    TrackRow(
        track = track,
        isCurrent = on,
        isPlaying = on && now.second,
        isFavorite = false,
        onClick = { if (selecting) Selecting.toggle(track, context, pool) else onPlay() },
        onToggleFavorite = null,
        onMore = { SongSheets.show(SongTarget.Song(track), context, pool) },
        status = status?.first,
        statusIcon = status?.second,
        onCoverClick = if (selecting) ({ Selecting.toggle(track, context, pool) }) else null,
        selected = selecting && sel.has(track.id),
        onLongClick = { Selecting.toggle(track, context, pool) },
        showDuration = false,
    )
}

/** For song cards: tap plays (or picks while selecting), long press opens the [SongSheet]. */
@Composable
fun Modifier.songClicks(track: Track, context: SongContext, pool: List<Track>, onPlay: () -> Unit): Modifier {
    val sel by Selecting.state.collectAsState()
    val selecting = sel.isSelecting(context)
    return combinedClickable(
        onClickLabel = if (selecting) "select" else "play",
        onLongClickLabel = "more options",
        onLongClick = { SongSheets.show(SongTarget.Song(track), context, pool) },
        onClick = { if (selecting) Selecting.toggle(track, context, pool) else onPlay() },
    )
}

/** The check drawn over a picked card's cover; put it last in the cover's Box. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.SelectedMark(track: Track, context: SongContext) {
    val sel by Selecting.state.collectAsState()
    if (!sel.isSelecting(context) || !sel.has(track.id)) return
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CheckCircle, "selected", tint = Accent, modifier = Modifier.size(36.dp))
    }
}

/** The selection bar shown over the bottom bar while songs are picked; Back closes it. */
@Composable
fun SelectionBar() {
    val repo = (LocalContext.current.applicationContext as SlowvaApp).repository
    val sel by Selecting.state.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf<List<Track>?>(null) }
    pick?.let { AddToPlaylistSheet(it, onDismiss = { pick = null }) }
    val context = sel.context
    if (!sel.active || context == null) return
    BackHandler { Selecting.clear() }
    val ids = sel.tracks.map { it.id }
    val done: (() -> Unit) -> Unit = { action -> action(); Selecting.clear() }
    Surface(Modifier.fillMaxWidth(), color = SurfaceElevated, shadowElevation = 4.dp) {
        // as tall as the tab bar it covers
        Row(Modifier.navigationBarsPadding().heightIn(min = 80.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = Selecting::clear) { Icon(Icons.Filled.Close, "Stop selecting", tint = TextPrimary) }
            Text("${sel.tracks.size} selected", color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (sel.tracks.size < sel.pool.size) IconButton(onClick = Selecting::selectAll) { Icon(Icons.Filled.SelectAll, "Select all", tint = TextPrimary) }
            IconButton(onClick = { done { pick = sel.tracks } }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", tint = TextPrimary) }
            IconButton(onClick = { done { SongActions.likeAll(repo, sel.tracks) } }) { Icon(Icons.Filled.Favorite, "Like", tint = TextPrimary) }
            SongActions.removeLabel(repo, context, ids)?.let { label ->
                IconButton(onClick = { done { SongActions.remove(repo, context, ids) } }) { Icon(Icons.Filled.Delete, label, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Shows [Snack] messages in [state], one at a time, newest first; Undo runs the message's undo. */
@Composable
fun rememberSnackState(): SnackbarHostState {
    val state = remember { SnackbarHostState() }
    LaunchedEffect(state) {
        Snack.messages.collect { m ->
            state.currentSnackbarData?.dismiss()
            launch {
                val r = state.showSnackbar(m.text, actionLabel = m.undo?.let { "Undo" }, withDismissAction = m.undo == null, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
            }
        }
    }
    return state
}

/** Hosts the [SongSheet] that [SongSheets.show] asks for. */
@Composable
fun SongSheetHost() {
    val request by SongSheets.open.collectAsStateWithLifecycle()
    request?.let { r ->
        val song = (r.target as? SongTarget.Song)?.track
        SongSheet(
            r.target, r.context,
            onDismiss = { SongSheets.open.value = null },
            onSelect = if (song != null && r.pool.isNotEmpty()) ({ Selecting.toggle(song, r.context, r.pool) }) else null,
        )
    }
}
