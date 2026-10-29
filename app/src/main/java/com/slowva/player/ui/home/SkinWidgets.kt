package com.slowva.player.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.data.Track
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.SkinView
import com.slowva.player.ui.formatMs
import com.slowva.player.ui.library.PlayList
import com.slowva.player.ui.skins.LiveProgress
import com.slowva.player.ui.skins.Skin
import com.slowva.player.ui.skins.radio.RadioSkin
import com.slowva.player.ui.skins.radio.RadioSkinView
import com.slowva.player.ui.skins.radio.aspectRatio
import com.slowva.player.ui.skins.rememberArtAccent
import com.slowva.player.ui.skins.rememberArtPainter
import com.slowva.player.ui.skins.rememberLiveProgress
import com.slowva.player.ui.songs.SongActions
import com.slowva.player.ui.theme.Appearance
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary

/** The accent the player uses: the cover's color when that setting is on, the theme's otherwise. */
@Composable
private fun playerAccent(cover: String?): Color {
    val coverColors by Appearance.coverColors.collectAsState()
    return rememberArtAccent(if (coverColors) cover else null, MaterialTheme.colorScheme.primary)
}

/** How wide each skin is drawn in a [width]-wide card; the tall ones are kept narrower. */
internal fun widgetSkinSide(skin: Skin, width: Dp): Dp = when (skin) {
    Skin.TAPE -> width
    Skin.TP7 -> minOf(width * 0.62f, 300.dp)
    else -> minOf(width * 0.82f, 340.dp)
}

/** One of the player's skins, live: it turns, scratches and plays like on the Now Playing screen. */
@Composable
internal fun SkinWidget(skin: Skin, onOpen: () -> Unit) {
    val state by PlayerHub.playback.collectAsStateWithLifecycle()
    val track = state?.track
    val live = rememberLiveProgress()
    val art = rememberArtPainter(track?.coverUri)
    val accent = playerAccent(track?.coverUri)
    WidgetCard(onOpen) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkinView(skin, live, art, accent, track, widgetSkinSide(skin, maxWidth), seek = false)
            // the skin would take drags as scratching or seeking; on a page they have to scroll it
            Box(Modifier.matchParentSize().clickable(onClickLabel = "open the player", onClick = onOpen))
        }
        Spacer(Modifier.height(10.dp))
        TrackLine(track?.title ?: "Nothing playing", track?.artist ?: "Pick a song and it shows up here", state?.isPlaying == true && track != null, accent)
    }
}

/** A radio face with the song playing now: the artist on the dial, the title on its display. */
@Composable
internal fun RadioWidget(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val state by PlayerHub.playback.collectAsStateWithLifecycle()
    val track = state?.track
    val skin = remember { RadioSkin.load(ctx) }
    val accent = playerAccent(track?.coverUri)
    val art = rememberArtPainter(track?.coverUri)
    val playing = track != null && state?.isPlaying == true
    WidgetCard(onOpen) {
        RadioSkinView(
            skin, track?.artist ?: "slowva radio", track?.title, art.takeIf { track?.coverUri != null }, accent,
            playing = playing, still = false, onTogglePlay = { PlayerHub.togglePlay() }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        TrackLine(track?.title ?: "Nothing playing", track?.artist ?: "Pick a song and it shows up here", playing, accent)
    }
}

/** Song, artist and previous / play / next under a skin. */
@Composable
private fun TrackLine(title: String, sub: String, playing: Boolean, accent: Color, onPlay: () -> Unit = { PlayerHub.togglePlay() }, skip: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (skip) IconButton(onClick = { PlayerHub.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous") }
        FilledIconButton(
            onClick = onPlay, modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Color.Black),
        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
        if (skip) IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Filled.SkipNext, "Next") }
    }
}

@Composable
private fun WidgetCard(onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "open the player", onClick = onOpen)
            .padding(16.dp),
        content = content,
    )
}

/**
 * The Now playing face: the cover fills the card, darkened toward the bottom and washed with the accent,
 * a moving equalizer badge on top and the song in big type. [bottom] holds the controls, or empty room for the home screen's.
 */
@Composable
internal fun NowPlayingFace(
    title: String,
    artist: String,
    art: Painter,
    accent: Color,
    playing: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF0B0D12))) {
        Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent))))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.45f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.92f)),
            ),
        )
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EqBadge(playing && animate, accent)
                Spacer(Modifier.width(8.dp))
                Text(label.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(title, color = Color.White, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            bottom()
        }
    }
}

/** Three little bars that bounce while music plays. */
@Composable
private fun EqBadge(moving: Boolean, accent: Color) {
    val t = if (moving) rememberInfiniteTransition(label = "eq").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "t").value else 0.35f
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0..2) {
            val h = if (moving) 0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin((t + i * 0.33f) * Math.PI.toFloat() * 2)) else 0.35f + i * 0.2f
            Box(Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(accent))
        }
    }
}

/** The new Now playing card for Home and Library: the face plus a live time bar and the controls. */
@Composable
internal fun NowPlayingWidget(repo: MusicRepository, liked: List<Track>, onOpen: () -> Unit) {
    val state by PlayerHub.playback.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val track = state?.track
    val art = rememberArtPainter(track?.coverUri)
    val accent = playerAccent(track?.coverUri)
    val playing = state?.isPlaying == true
    NowPlayingFace(
        title = track?.title ?: "Nothing playing",
        artist = track?.artist ?: "Tap to shuffle your liked songs",
        art = art, accent = accent, playing = playing,
        label = if (track == null) "Slowva" else if (playing) "Now playing" else "Paused",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).height(320.dp)
            .clickable(onClickLabel = "open the player") { if (track != null) onOpen() else PlayList.shuffle(repo, liked) },
    ) {
        if (track == null) return@NowPlayingFace
        Spacer(Modifier.height(12.dp))
        LiveBar(accent)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val shuffle = state?.shuffle == true
            IconButton(onClick = { PlayerHub.toggleShuffle() }) { Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (shuffle) accent else Color.White.copy(alpha = 0.7f)) }
            IconButton(onClick = { PlayerHub.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(34.dp), tint = Color.White) }
            FilledIconButton(
                onClick = { PlayerHub.togglePlay() }, modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
            ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp)) }
            IconButton(onClick = { PlayerHub.next() }) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(34.dp), tint = Color.White) }
            val liked = track.id in favorites
            IconButton(onClick = { SongActions.toggleLike(repo, track) }) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Unlike" else "Like", tint = if (liked) accent else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** Kept apart so only the bar redraws every frame. */
@Composable
private fun LiveBar(accent: Color) {
    val live = rememberLiveProgress()
    val p = live.fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(p).height(4.dp).clip(CircleShape).background(accent))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(formatMs(live.positionMs.toInt()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(formatMs(live.durationMs.toInt()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}

/** A frozen copy of the player's progress, for drawing a skin once (the home screen widgets). */
internal fun stillProgress(positionMs: Long, durationMs: Long, playing: Boolean) = LiveProgress(still = true).apply {
    this.durationMs = durationMs.coerceAtLeast(1)
    anchorMs = positionMs
    this.playing = playing
}

/** The radio face for one song, frozen, for the home screen widget; [dial] is the artist. Fits [width] x [height]. */
@Composable
internal fun RadioStill(skin: RadioSkin, dial: String?, liveTitle: String?, art: Painter?, accent: Color, playing: Boolean, width: Dp, height: Dp) {
    RadioSkinView(
        skin, dial ?: "slowva radio", liveTitle, art, accent, playing = playing, still = true,
        onTogglePlay = {}, modifier = Modifier.width(minOf(width, height * skin.aspectRatio())),
    )
}
