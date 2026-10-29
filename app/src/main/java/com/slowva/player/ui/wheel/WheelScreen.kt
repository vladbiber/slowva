package com.slowva.player.ui.wheel

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.slowva.player.SlowvaApp
import com.slowva.player.data.Track
import com.slowva.player.ui.songs.SongActions
import com.slowva.player.ui.songs.SongContext
import com.slowva.player.ui.songs.SongSheets
import com.slowva.player.ui.songs.SongTarget
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.slowva.player.ui.PillTabs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slowva.player.data.MusicRepository
import com.slowva.player.ui.EmptyHint
import com.slowva.player.ui.theme.Accent
import com.slowva.player.ui.theme.BgBlack
import com.slowva.player.ui.theme.TextMuted
import com.slowva.player.ui.theme.TextPrimary
import com.slowva.player.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Full screen wheel; [library] adds "Your library" first, then [sources]. */
@Composable
fun WheelScreen(
    onClose: () -> Unit,
    library: MusicRepository? = null,
    sources: List<WheelSource> = emptyList(),
    title: String = "Spin the wheel",
    notice: String? = null,
) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as SlowvaApp).repository
    // songs share the app's heart and sheet; other items keep their source's heart
    fun songOf(item: WheelItem): Track? = item.ref as? Track
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val picker = remember { WheelPicker(ctx) }
    val motion = remember { WheelMotion() }
    val glow = remember { Animatable(0f) }
    val reduced = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val playlists = library?.playlists?.collectAsStateWithLifecycle()?.value.orEmpty()

    var libScope by remember { mutableStateOf<LibraryScope>(LibraryScope.All) }
    val all = remember(library, libScope, sources) {
        listOfNotNull(library?.let { LibrarySource(it, libScope) }) + sources
    }
    var selectedId by remember { mutableStateOf(all.firstOrNull { it.unavailable == null }?.id) }
    val source = all.firstOrNull { it.id == selectedId && it.unavailable == null }
    var mode by remember { mutableStateOf(picker.mode) }
    var round by remember { mutableIntStateOf(0) }
    var wheel by remember { mutableStateOf<Wheel?>(null) }
    var loading by remember { mutableStateOf(true) }
    var landed by remember { mutableStateOf<WheelItem?>(null) }
    var hearted by remember { mutableStateOf(false) }
    var pendingSpin by remember { mutableStateOf<Float?>(null) }
    var lucky by remember { mutableStateOf(false) }

    fun spin(w: Wheel, v0: Float) {
        val src = source ?: return
        if (motion.spinning) return
        landed = null
        motion.slices = w.slices.size
        scope.launch {
            glow.snapTo(0f)
            val slot = motion.spin(
                target = w.winner, v0 = v0,
                timing = if (reduced) SpinTiming.Reduced else SpinTiming.Normal,
                rnd = picker::nextFloat,
                prepare = { t ->
                    if (src.prepare(w.slices[t])) t
                    // the winner can't play: quietly take another slice instead
                    else w.slices.indices.filter { it != t }.shuffled(java.util.Random(picker.nextInt(Int.MAX_VALUE).toLong()))
                        .firstOrNull { src.prepare(w.slices[it]) } ?: t
                },
                onTick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            )
            val item = w.slices[slot]
            if (slot != w.winner) wheel = w.copy(winner = slot)
            picker.commit(src.poolKey, item.id)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            src.play(item, w.slices)
            hearted = songOf(item)?.let { repo.isFavorite(it.id) } ?: src.isFavorite(item)
            landed = item
            glow.animateTo(1f, tween(if (reduced) 0 else 520))
        }
    }

    // build a wheel: the winner is already chosen when it appears
    LaunchedEffect(source?.poolKey, mode, round) {
        val src = source ?: run { wheel = null; loading = false; return@LaunchedEffect }
        loading = true
        val pool = src.pool()
        val w = picker.build(src.poolKey, pool, src::isFavorite)
        glow.snapTo(0f)
        landed = null
        if (w != null) motion.slices = w.slices.size
        wheel = w
        loading = false
        val v = pendingSpin
        pendingSpin = null
        if (w != null && v != null) spin(w, v)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary) }
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (library != null) {
            PillTabs(
                listOf("My songs", "I'm feeling lucky"), if (lucky) 1 else 0,
                onSelect = { if (!motion.spinning) lucky = it == 1 },
            )
            if (lucky) {
                LuckyWheel(library)
                return@Column
            }
        }
        val busy = motion.spinning
        WheelSourceBar(
            all, selectedId, libScope, playlists, mode, enabled = !busy,
            onPick = { id, sc -> selectedId = id; if (sc != null) libScope = sc },
            onMode = { mode = it; picker.mode = it },
        )
        notice?.let {
            Text(it, color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        val w = wheel
        when {
            w != null -> {
                Spacer(Modifier.height(4.dp))
                WheelStage(
                    w, motion, glow = { glow.value },
                    onFling = { v -> if (landed != null) { pendingSpin = v; round++ } else spin(w, v) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text(
                    odds(w, mode, source?.noun ?: "songs"),
                    color = TextMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            loading -> Spacer(Modifier.height(320.dp))
            source == null -> EmptyHint("Nothing to spin yet.")
            source is LibrarySource -> EmptyHint(
                when (libScope) {
                    LibraryScope.All -> "Import some music from the Library tab first."
                    LibraryScope.Favorites -> "Heart a few songs first."
                    is LibraryScope.InPlaylist -> "This playlist is empty."
                }
            )
            else -> EmptyHint("No ${source.noun} saved here yet. Open the tab once to load them.")
        }

        Spacer(Modifier.height(12.dp))
        val item = landed
        val src = source
        if (item != null && src != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { songOf(item)?.let { SongActions.toggleLike(repo, it) } ?: src.toggleFavorite(item); hearted = !hearted }) {
                        Icon(
                            if (hearted) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (hearted) "Unlike" else "Like",
                            tint = if (hearted) Accent else TextSecondary,
                        )
                    }
                    songOf(item)?.let { t ->
                        IconButton(onClick = { SongSheets.show(SongTarget.Song(t), SongContext.Other) }) {
                            Icon(Icons.Filled.MoreVert, "More options for ${item.title}", tint = TextSecondary)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    SpinButton("Spin again", enabled = !busy) { pendingSpin = 0f; round++ }
                }
            }
        } else if (w != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpinButton(if (busy) "Spinning…" else "Spin", enabled = !busy) { spin(w, 0f) }
            }
            Text(
                "or fling the wheel", color = TextMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpinButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = Modifier.height(52.dp),
    ) {
        Icon(Icons.Filled.Casino, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

private fun odds(w: Wheel, mode: WheelMode, noun: String): String {
    val n = "%,d".format(w.poolSize)
    return when (mode) {
        WheelMode.PURE -> "1 in $n $noun"
        WheelMode.NO_REPEATS -> if (w.left >= w.poolSize) "1 in $n $noun" else "1 in ${"%,d".format(w.left)} $noun left of $n"
        WheelMode.FAVORITES -> "$n $noun, favorites count three times"
    }
}
