package com.slowva.player.ui

import androidx.compose.foundation.progressSemantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import com.slowva.player.ui.theme.Appearance
import com.slowva.player.ui.theme.ThemeSource
import com.slowva.player.ui.theme.accentBrush
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.slowva.player.R
import com.slowva.player.ui.art.GeneratedCover
import com.slowva.player.ui.art.coverTones
import com.slowva.player.data.Track
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.SurfaceElevated
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

fun formatMs(ms: Int): String = formatTime(ms.toLong())

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

/** What a track's generated cover is named after: its album, or its title for loose songs. */
internal fun coverName(track: Track?): String {
    val album = track?.album?.trim().orEmpty()
    return if (album.isNotEmpty() && album != "Imported") album else track?.title.orEmpty()
}

@Composable
fun AlbumArt(track: Track?, size: Dp, shape: Shape = MaterialTheme.shapes.extraSmall, modifier: Modifier = Modifier.size(size)) {
    CoverImage(track?.coverUri, coverName(track), shape, modifier)
}

/** A cover picture, or the [GeneratedCover] of [name] when there is none or it fails to load. */
@Composable
fun CoverImage(uri: Any?, name: String, shape: Shape, modifier: Modifier) {
    Box(modifier.clip(shape).background(coverTones(name).paper), contentAlignment = Alignment.Center) {
        if (uri == null) GeneratedCover(name, Modifier.fillMaxSize())
        else SubcomposeAsyncImage(
            model = uri,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { },
            error = { GeneratedCover(name, Modifier.fillMaxSize()) },
        )
    }
}

/** The Slowva logo: the stretched waveform from the launcher icon. */
@Composable
fun SlowvaLogo(size: Dp) {
    Image(
        painterResource(moodLogo()), "Slowva logo",
        modifier = Modifier.size(size),
    )
}

/** Evil, Godly and Good & Evil modes keep their own logos. */
@Composable
private fun moodLogo(): Int {
    val mood by com.slowva.player.player.fx.Moods.mood.collectAsState()
    return when (mood) {
        com.slowva.player.player.fx.Mood.EVIL -> R.drawable.logo_guava_evil
        com.slowva.player.player.fx.Mood.GODLY -> R.drawable.logo_guava_godly
        com.slowva.player.player.fx.Mood.GOOD_EVIL -> R.drawable.logo_guava_goodevil
        com.slowva.player.player.fx.Mood.NORMAL -> R.drawable.ic_launcher_foreground
    }
}

/** Playlist artwork: the custom cover if one was set, else the first track's art, else a cover generated from its name. */
@Composable
fun PlaylistArt(
    playlist: com.slowva.player.data.Playlist,
    firstTrack: Track?,
    size: Dp,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    modifier: Modifier = Modifier.size(size),
) {
    CoverImage(playlist.coverUri ?: firstTrack?.coverUri, playlist.name, shape, modifier)
}

@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    /** the ⋮ button; a long press does the same */
    onMore: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    /** small line before the artist, e.g. "Online" or "Downloaded" */
    status: String? = null,
    statusIcon: ImageVector? = null,
    /** a tap on the cover picks the song for multi-select */
    onCoverClick: (() -> Unit)? = null,
    selected: Boolean = false,
    /** the + button that opens the playlist picker */
    onAddToPlaylist: (() -> Unit)? = null,
    /** long press; defaults to [onMore] */
    onLongClick: (() -> Unit)? = onMore,
    showDuration: Boolean = true,
) {
    val titleColor by animateColorAsState(if (isCurrent) Accent else TextPrimary, label = "title")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else if (isCurrent) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, Accent, MaterialTheme.shapes.small) else Modifier)
            .combinedClickable(onClickLabel = "play", onLongClickLabel = if (onLongClick === onMore) "more options" else "select", onLongClick = onLongClick, onClick = onClick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${track.title}, by ${track.artist}, ${formatTime(track.durationMs)}"
                if (isCurrent) stateDescription = if (isPlaying) "now playing" else "paused"
                if (selected) stateDescription = "selected"
            }.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    if (onCoverClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = if (selected) "unselect" else "select", onClick = onCoverClick) else Modifier,
                    contentAlignment = Alignment.Center,
                ) {
                    AlbumArt(track, 50.dp, shape = MaterialTheme.shapes.small)
                    if (selected || isCurrent) {
                        Box(
                            Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.Pause,
                                null, tint = Accent, modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = titleColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (statusIcon != null) Icon(statusIcon, null, tint = TextMuted, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                        Text(
                            if (status != null) "$status · ${track.artist}" else track.artist,
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showDuration) Text(formatTime(track.durationMs), color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (isFavorite) "Unlike ${track.title}" else "Like ${track.title}",
                    tint = if (isFavorite) Accent else TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add ${track.title} to a playlist", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.RemoveCircleOutline, "Remove ${track.title} from playlist", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, "More options for ${track.title}", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    /** the full player is open: the cover hands over to it (see [sharedCover]) */
    playerOpen: Boolean = false,
) {
    val duo = Appearance.source.collectAsState().value == ThemeSource.DUO
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val openPlayer by rememberUpdatedState(onClick)
    val swipeAt = with(LocalDensity.current) { 72.dp.toPx() }
    val nextLabel = "Next track"
    val previousLabel = "Previous track"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            // swipe up opens the player, like a tap
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -swipeAt / 2) openPlayer() }) { change, amount ->
                    change.consume(); dy += amount
                }
            }
            // swipe left = next, right = previous; taps still open the player
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dx.value <= -swipeAt) next() else if (dx.value >= swipeAt) previous()
                        scope.launch { dx.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { dx.snapTo(dx.value + amount) }
                }
            }
            .graphicsLayer {
                translationX = dx.value
                alpha = 1f - (abs(dx.value) / size.width).coerceAtMost(0.4f)
            }
            .clip(MaterialTheme.shapes.medium)
            .background(SurfaceElevated),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "open player", onClick = onClick)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { next(); true },
                        CustomAccessibilityAction(previousLabel) { previous(); true },
                    )
                }
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // a fixed slot, so the row keeps its layout while the cover is away in the player
            Box(Modifier.size(44.dp)) {
                androidx.compose.animation.AnimatedVisibility(!playerOpen, enter = fadeIn(), exit = fadeOut()) {
                    AlbumArt(track, 44.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.sharedCover(this).size(44.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    track.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.marquee(),
                )
                Text(
                    track.artist, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.marquee(),
                )
            }
            PlayPauseButton(isPlaying, onTogglePlay, Accent, brush = if (duo) accentBrush() else null, iconSize = 24.dp)
            CtIconButton(Icons.Filled.SkipNext, nextLabel, onNext, tint = TextPrimary)
        }
        if (duo) {
            val fill = accentBrush()
            val track = TextPrimary.copy(alpha = 0.15f)
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(2.dp)
                    .progressSemantics(progress.coerceIn(0f, 1f))
                    .drawBehind {
                        drawRect(track)
                        drawRect(fill, size = size.copy(width = size.width * progress.coerceIn(0f, 1f)))
                    },
            )
        } else LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(2.dp),
            color = Accent,
            trackColor = TextPrimary.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** A cover, a title and a subtitle in one tappable row: albums, playlists, matches. */
@Composable
internal fun CardRow(image: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        coil.compose.AsyncImage(image, null, Modifier.size(56.dp).clip(MaterialTheme.shapes.extraSmall))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
