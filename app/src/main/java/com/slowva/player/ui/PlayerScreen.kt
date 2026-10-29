package com.slowva.player.ui

import com.slowva.player.ui.mood.moodText
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import com.slowva.player.ui.skins.stageSemantics

import androidx.compose.material.icons.automirrored.filled.QueueMusic

import androidx.compose.material.icons.automirrored.filled.PlaylistAdd

import com.slowva.player.ui.skins.radio.RadioSkin
import com.slowva.player.ui.skins.radio.RadioSkinView
import com.slowva.player.ui.skins.radio.aspectRatio
import androidx.compose.foundation.layout.aspectRatio
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import com.slowva.player.ui.player.SongPlayerMenu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import com.slowva.player.ui.player.SleepSheet
import com.slowva.player.ui.player.sleepLeft
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.draw.alpha
import com.slowva.player.ui.songs.Snack
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.slowva.player.SlowvaApp
import com.slowva.player.ui.theme.Appearance
import com.slowva.player.ui.theme.LiquidBackground
import com.slowva.player.ui.theme.ThemeSource
import com.slowva.player.ui.theme.accentBrush
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.theme.boosted
import com.slowva.player.ui.theme.rememberMusicPulse
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawBehind
import com.slowva.player.ui.visualizer.Visualizer
import com.slowva.player.ui.visualizer.VisualizerView
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import com.slowva.player.lyrics.Lyrics
import com.slowva.player.lyrics.LyricsRepository
import com.slowva.player.ui.player.LyricsView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.ColumnScope
import com.slowva.player.ui.skins.CdSkin
import com.slowva.player.ui.skins.LiveProgress
import com.slowva.player.ui.skins.PillSlider
import com.slowva.player.ui.skins.Skin
import com.slowva.player.ui.skins.TapeSkin
import com.slowva.player.ui.skins.Tp7Skin
import com.slowva.player.ui.skins.VinylSkin
import com.slowva.player.ui.skins.rememberArtAccent
import com.slowva.player.ui.skins.rememberArtPainter
import com.slowva.player.ui.skins.rememberLiveProgress
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.WaterDrop
import com.slowva.player.player.fx.RainPreset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.slowva.player.player.fx.RETUNE_PRESETS
import kotlin.math.abs
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.slowva.player.data.Track
import com.slowva.player.player.sound.GenrePreset
import com.slowva.player.player.sound.SoundSettings
import com.slowva.player.ui.player.SkinPicker

private val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
private val InkDim: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(onClose: () -> Unit, onOpenPlaylist: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val state by PlayerHub.playback.collectAsState()
    val track = state?.track
    var skin by remember { mutableStateOf(Skin.load(ctx)) }
    // a visualizer, when chosen, takes the stage instead of the skin
    var visualizer by remember { mutableStateOf(Visualizer.load(ctx)) }
    var radioSkin by remember { mutableStateOf(RadioSkin.load(ctx)) }
    // a radio skin shows the song like a station on its dial
    var radioOn by remember { mutableStateOf(RadioSkin.isOn(ctx)) }
    var sheet by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // null while looking; fetched for the Now Playing side only, so the button can grey out early
    val lyrics by produceState<Lyrics?>(null, track?.id) {
        value = track?.let(LyricsRepository::cached)
        if (value == null && track != null) value = LyricsRepository.get(track)
    }
    val coverColors by Appearance.coverColors.collectAsState()
    val accent = rememberArtAccent(if (coverColors) track?.coverUri else null, MaterialTheme.colorScheme.primary)
    val art = rememberArtPainter(track?.coverUri)
    val live = rememberLiveProgress()
    val source by Appearance.source.collectAsState()
    // two-color fade on the small spots; cover colors win when they are on
    val fill: Brush? = if (source == ThemeSource.DUO && !coverColors) accentBrush() else null

    val scope = rememberCoroutineScope()
    val drop = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }

    // not inside a Surface, so set the text color here or bare Text turns black
    // lyrics read best on plain black: drop the cover backdrop and draw the player in light-on-black colors
    val lyricsMode = showLyrics && lyrics?.found == true
    val base = MaterialTheme.colorScheme
    val playerScheme = if (!lyricsMode) base else base.copy(
        background = Color.Black, onBackground = Color.White, onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.7f),
        surfaceContainerHigh = base.surfaceContainerHigh,
    )
    MaterialTheme(colorScheme = playerScheme) {
    CompositionLocalProvider(LocalContentColor provides Ink) {
    Box(
        Modifier
            .fillMaxSize()
            // drag down where no skin, slider or scroll claims the touch: follows the finger, then the usual slide-out
            // shortcut: the compact layout's scroll takes vertical drags, so there only the arrow and Back close
            .draggable(
                rememberDraggableState { d -> scope.launch { drop.snapTo((drop.value + d).coerceAtLeast(0f)) } },
                Orientation.Vertical,
                onDragStopped = { v -> if (drop.value > closeAt || v > 2_000f) onClose() else drop.animateTo(0f) },
            )
            .graphicsLayer { translationY = drop.value }
            .background(MaterialTheme.colorScheme.background),
    ) {
        val pulse = rememberMusicPulse(live.playing)
        AnimatedVisibility(!lyricsMode, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Backdrop(track?.coverUri, accent, pulse)
                if (!coverColors) LiquidBackground(Modifier.fillMaxSize(), pulse)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            // very short screens (or huge fonts) scroll; otherwise the stage takes what is left
            val compact = maxHeight < 560.dp
            val main: @Composable ColumnScope.() -> Unit = {
                PlayerTopBar(
                    onClose = onClose, onMenu = { menu = true },
                    canLyrics = track != null, lyrics = lyrics, showLyrics = showLyrics, accent = accent,
                    onLyrics = { showLyrics = it },
                )
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .then(if (compact) Modifier.height(340.dp) else Modifier.weight(1f))
                        // the stage belongs to the skins (scratch, shuttle, tap to play), so drags here never close the player
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                do {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                } while (e.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val stageMax = maxWidth
                    val stageHeight = maxHeight
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        // lyrics on the left, the skin shrinks to the right
                        val lyricsOn = showLyrics && lyrics?.found == true
                        AnimatedVisibility(lyricsOn, Modifier.weight(1f), enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                            lyrics?.let { LyricsView(it, live, Ink, InkDim, Modifier.fillMaxSize().padding(end = 8.dp)) }
                        }
                        val stageWidth by animateDpAsState(if (lyricsOn) stageMax * 0.42f else stageMax, label = "stage")
                        Box(Modifier.width(stageWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val visibility = LocalPlayerVisibility.current
                            AnimatedContent(
                                targetState = visualizer ?: if (radioOn) radioSkin else skin,
                                // fills the stage, so a smaller or larger skin never moves what is around it
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                                transitionSpec = {
                                    val fade = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                                    (fadeIn(fade) + scaleIn(fade, initialScale = 0.96f)) togetherWith
                                        (fadeOut(fade) + scaleOut(fade, targetScale = 1.02f)) using SizeTransform(clip = false)
                                },
                                label = "skin",
                            ) { s ->
                                val shared = Modifier.sharedCover(visibility)
                                if (s is Visualizer) Box(shared) { VisualizerView(s, art, live.playing, still = false, side = minOf(stageWidth, stageHeight)) }
                                else if (s is Skin) SkinView(s, live, art, accent, track, skinSide(s, stageWidth, stageHeight), seek = true, fill = fill, modifier = shared)
                                else if (s is RadioSkin) RadioSkinView(
                                    s, track?.artist ?: "slowva radio", track?.title, art, accent, live.playing, still = false,
                                    onTogglePlay = { PlayerHub.togglePlay() }, modifier = shared.then(radioSkinSize(s, stageWidth, stageHeight)),
                                )
                            }
                        }
                    }
                }
                NowPlayingDetails(
                    live, accent, seekBar = visualizer != null || skin != Skin.TAPE && skin != Skin.COVER, fill = fill,
                    onSleep = { sleep = true }, onSound = { sheet = true },
                )
            }
            val pad = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            // one rhythm between top bar, stage and controls
            val rhythm = Arrangement.spacedBy(12.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .then(pad),
                verticalArrangement = rhythm,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = main,
            )
        }
    }
    }
    }

    if (sheet) {
        ModalBottomSheet(
            onDismissRequest = { sheet = false },
            // every sound setting is a long scroll, so it opens tall
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            SoundSheet(onLook = { sheet = false; picker = true })
        }
    }
    if (picker) {
        val still = remember { LiveProgress(still = true) }
        SkinPicker<Enum<*>>(
            tabs = listOf("Skins" to Skin.entries, "Radios" to RadioSkin.entries, "Visualizers" to Visualizer.entries),
            current = visualizer ?: if (radioOn) radioSkin else skin,
            label = { (it as? Skin)?.label ?: (it as? RadioSkin)?.label ?: (it as Visualizer).label },
            preview = { item, space ->
                if (item is Skin) SkinView(item, still, art, accent, track, skinSide(item, space.width, space.height), seek = false)
                else if (item is Visualizer) VisualizerView(item, art, playing = false, still = true, side = minOf(space.width, space.height))
                else if (item is RadioSkin) RadioSkinView(
                    item, track?.artist ?: "slowva radio", track?.title, art, accent, playing = false, still = true,
                    onTogglePlay = {}, modifier = radioSkinSize(item, space.width, space.height),
                )
            },
            onSelect = {
                when (it) {
                    is Skin -> { skin = it; Skin.save(ctx, it); visualizer = null; radioOn = false }
                    is RadioSkin -> { radioSkin = it; RadioSkin.save(ctx, it); visualizer = null; radioOn = true }
                    else -> visualizer = it as Visualizer
                }
                Visualizer.save(ctx, visualizer)
                RadioSkin.setOn(ctx, radioOn)
                picker = false
            },
            onDismiss = { picker = false },
        )
    }
    if (menu) {
        val onLook = { picker = true }
        val onSleep = { sleep = true }
        track?.let {
            SongPlayerMenu(it, onOpenPlaylist = { name -> onClose(); onOpenPlaylist(name) }, onDismiss = { menu = false }, onLook = onLook, onSleep = onSleep)
        } ?: run { menu = false }
    }
    if (sleep) {
        ModalBottomSheet(
            onDismissRequest = { sleep = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            SleepSheet(onDone = { sleep = false })
        }
    }
}

/** Pastel cover accents are too light on a light background; keep the hue, lower the lightness. */
private fun Color.darkened(): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = hsl[2].coerceAtMost(0.40f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/** Width a skin gets in a [maxWidth] x [h] stage. */
internal fun skinSide(skin: Skin, maxWidth: Dp, h: Dp): Dp = when (skin) {
    Skin.TP7 -> minOf(maxWidth - 24.dp, h / 1.3f)
    Skin.TAPE -> minOf(maxWidth, (h - 48.dp) * 1.6f)
    Skin.COVER -> minOf(maxWidth, h - 56.dp)
    else -> minOf(maxWidth, h)
}.coerceIn(120.dp, 480.dp)

/** One skin drawn for [track]; [seek] adds the cover's own seek row; [modifier] goes on the skin itself. */
@Composable
internal fun SkinView(s: Skin, live: LiveProgress, art: Painter, accent: Color, track: Track?, side: Dp, seek: Boolean, fill: Brush? = null, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val m = modifier.width(side)
        val title = track?.title ?: "Nothing playing"
        when (s) {
            Skin.VINYL -> VinylSkin(live, art, accent, m, title)
            Skin.CD -> CdSkin(live, art, accent, m, title)
            Skin.TAPE -> TapeSkin(live, art, accent, track?.album ?: track?.title ?: "slowva", m)
            Skin.TP7 -> Tp7Skin(live, art, accent, track?.let { "${it.title} · ${it.artist}" } ?: "slowva", m)
            Skin.COVER -> {
                PlainCover(live, art, m)
                if (seek) SeekRow(live, accent, Modifier.width(side * 0.86f), fill)
            }
        }
    }
}

/** Blurred cover under a scrim; with a [pulse] the colors are boosted and swell with the bass. */
@Composable
private fun Backdrop(art: String?, accent: Color, pulse: State<Float>? = null) {
    val scrim = MaterialTheme.colorScheme.background
    val blurry = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val glow = if (pulse != null) accent.boosted() else accent
    val artAlpha = if (blurry) 0.7f else 0.28f
    AsyncImage(
        model = art,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            // pulse is read in the layer and draw blocks only, so beats never recompose the player
            .graphicsLayer { val e = pulse?.value ?: 0f; scaleX = 1.25f + 0.08f * e; scaleY = scaleX }
            .then(if (blurry) Modifier.blur(70.dp) else Modifier)
            .graphicsLayer { alpha = if (pulse == null) artAlpha else (artAlpha * 1.25f + 0.15f * pulse.value).coerceAtMost(1f) },
    )
    Box(
        Modifier.fillMaxSize().drawBehind {
            val e = pulse?.value ?: 0f
            // the lower stops stay as they were, so the controls and text keep their contrast
            val top = if (pulse == null) 0.10f else 0.22f + 0.18f * e
            drawRect(Brush.verticalGradient(listOf(glow.copy(alpha = top), scrim.copy(alpha = 0.25f), scrim.copy(alpha = 0.7f))))
            if (e > 0f) drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.35f * e), Color.Transparent), Offset(size.width / 2, size.height * 0.35f), size.maxDimension * 0.6f),
                size.maxDimension * 0.6f, Offset(size.width / 2, size.height * 0.35f),
            )
        }
    )
}

/** Close, the Now Playing / Radio switch, lyrics and the menu: the player's only top row. */
@Composable
private fun PlayerTopBar(
    onClose: () -> Unit, onMenu: () -> Unit,
    canLyrics: Boolean, lyrics: Lyrics?, showLyrics: Boolean, accent: Color, onLyrics: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CtIconButton(Icons.Default.KeyboardArrowDown, "Close player", onClose, tint = Ink)
        Text(
            moodText("Now Playing"), style = MaterialTheme.typography.labelLarge, color = InkDim, maxLines = 1,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
        )
        // the slot stays when there are no lyrics, so the switch never shifts
        if (canLyrics) LyricsToggle(lyrics, showLyrics, accent, onLyrics) else Spacer(Modifier.size(48.dp))
        CtIconButton(Icons.Default.MoreVert, "Song menu", onMenu, tint = Ink)
    }
}

/** Lyrics on or off; dimmed while looking or when the song has none, but a tap still answers with a message. */
@Composable
private fun LyricsToggle(lyrics: Lyrics?, on: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    val found = lyrics?.found == true
    CtToggleIconButton(
        checked = on && found, icon = Icons.Default.Lyrics, accent = accent,
        onCheckedChange = {
            when {
                found -> onToggle(it)
                lyrics == null -> Snack.show("Still looking for lyrics…")
                else -> Snack.show("No lyrics found for this song")
            }
        },
        modifier = Modifier.alpha(if (found) 1f else 0.4f),
        contentDescription = when {
            lyrics == null -> "Lyrics, looking for them"
            !found -> "Lyrics, none found for this song"
            else -> "Lyrics"
        },
    )
}

/** The album art itself in an expressive cookie shape, for the plain Cover look. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlainCover(live: LiveProgress, art: Painter, modifier: Modifier) {
    val shape = MaterialShapes.Cookie12Sided.toShape()
    Box(modifier.aspectRatio(1f).stageSemantics("Album cover", live), contentAlignment = Alignment.Center) {
        Image(
            art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize(0.9f)
                .shadow(16.dp, shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

@Composable
private fun SeekRow(live: LiveProgress, accent: Color, modifier: Modifier, fill: Brush? = null) {
    Column(modifier) {
        PillSlider(
            value = live.fraction,
            onChange = { live.preview = (it * live.durationMs).toLong() },
            onDone = { live.seekTo((it * live.durationMs).toLong()) },
            accent = accent,
            label = "Position",
            stateText = "${formatMs(live.positionMs.toInt())} of ${formatMs(live.durationMs.toInt())}",
            brush = fill,
            wave = rememberWave(live.playing && live.preview == null),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(live.positionMs.toInt()), style = MaterialTheme.typography.labelSmall, color = InkDim)
            Text(formatMs(live.durationMs.toInt()), style = MaterialTheme.typography.labelSmall, color = InkDim)
        }
    }
}

/** Title and artist set big, then seek, transport and the two sheet buttons; no card behind them. */
@Composable
private fun NowPlayingDetails(live: LiveProgress, accent: Color, seekBar: Boolean, fill: Brush?, onSleep: () -> Unit, onSound: () -> Unit) {
    val state by PlayerHub.playback.collectAsState()
    val track = state?.track
    val repository = (LocalContext.current.applicationContext as SlowvaApp).repository
    val favorites by repository.favorites.collectAsState()
    val fav = track != null && track.id in favorites

    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    track?.title ?: "Nothing playing", color = Ink, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.marquee(),
                )
                Text(
                    track?.artist ?: "pick something in Library",
                    color = InkDim, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.marquee(),
                )
            }
            CtToggleIconButton(
                checked = fav,
                onCheckedChange = { track?.let { repository.toggleFavorite(it.id) } },
                icon = Icons.Default.FavoriteBorder, checkedIcon = Icons.Default.Favorite,
                contentDescription = if (fav) "Remove from favorites" else "Add to favorites",
                accent = accent, pop = true,
            )
            if (track != null) {
                var pick by remember { mutableStateOf(false) }
                CtIconButton(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", { pick = true }, tint = Ink)
                if (pick) AddToPlaylistSheet(listOf(track), onDismiss = { pick = false })
            }
        }
        if (seekBar) SeekRow(live, accent, Modifier.fillMaxWidth().padding(horizontal = 4.dp), fill)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CtToggleIconButton(state?.shuffle == true, { PlayerHub.toggleShuffle() }, Icons.Default.Shuffle, "Shuffle", accent, dense = true)
            CtIconButton(Icons.Default.SkipPrevious, "Previous track", { PlayerHub.previous() })
            PlayPauseButton(state?.isPlaying == true, { PlayerHub.togglePlay() }, accent, Modifier.size(width = 76.dp, height = 64.dp), brush = fill)
            CtIconButton(Icons.Default.SkipNext, "Next track", { PlayerHub.next() })
            val repeat = state?.repeat ?: "off"
            CtToggleIconButton(
                repeat != "off", { PlayerHub.cycleRepeat() },
                if (repeat == "track") Icons.Default.RepeatOne else Icons.Default.Repeat,
                if (repeat == "track") "Repeat one" else "Repeat", accent, dense = true,
            )
        }
        // only while a timer runs; setting one lives in the menu
        sleepLeft()?.let { left ->
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "change the sleep timer", onClick = onSleep)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Bedtime, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pausing $left", style = MaterialTheme.typography.labelLarge, color = Ink)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerSheetButton(Icons.Default.Tune, moodText("Sound"), Modifier.weight(1f), onSound)
            var queue by rememberSaveable { mutableStateOf(false) }
            PlayerSheetButton(Icons.AutoMirrored.Filled.QueueMusic, moodText("Up next"), Modifier.weight(1f)) { queue = true }
            if (queue) QueueSheet(onDismiss = { queue = false })
        }
    }
}

@Composable
private fun PlayerSheetButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}

/** Fits a radio skin into the stage at its own width/height ratio. */
private fun radioSkinSize(s: RadioSkin, maxW: Dp, maxH: Dp): Modifier {
    val r = s.aspectRatio()
    val w = minOf(maxW, maxH * r)
    return Modifier.width(w).aspectRatio(r)
}

/** Set by SlowvaRoot: the layout the cover is shared across, and the player overlay's visibility. */
internal val LocalSharedCover = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalPlayerVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The mini player's cover and the player stage share these bounds, so one grows out of the other. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.sharedCover(visibility: AnimatedVisibilityScope?): Modifier {
    val shared = LocalSharedCover.current
    if (shared == null || visibility == null) return this
    return with(shared) { sharedBounds(rememberSharedContentState("cover"), visibility) }
}
