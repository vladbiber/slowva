package com.slowva.player.ui.skins

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.slowva.player.player.PlayerHub
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt

enum class Skin(val label: String) {
    VINYL("Vinyl"), CD("CD"), TAPE("Tape"), TP7("TP-7"), COVER("Cover");

    companion object {
        private const val PREFS = "slowva-ui"

        fun load(ctx: Context): Skin = runCatching {
            valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("skin", null)!!)
        }.getOrDefault(VINYL)

        fun save(ctx: Context, skin: Skin) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skin", skin.name).apply()
        }
    }
}

/**
 * Track position between the 3 s Spotify polls. On the phone the track runs
 * at the slowed rate, so the clock does too. A local seek shows up at once.
 */
@Stable
class LiveProgress {
    var anchorMs by mutableLongStateOf(0L)
    var anchorAt by mutableLongStateOf(0L)
    var clock by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var durationMs by mutableLongStateOf(1L)
    /** while the user drags a seek control, the position it points at */
    var preview by mutableStateOf<Long?>(null)
    private var seekedAt = Long.MIN_VALUE / 2

    val positionMs: Long
        get() {
            preview?.let { return it }
            val run = if (playing) ((clock - anchorAt).coerceAtLeast(0) * speed).toLong() else 0L
            return (anchorMs + run).coerceIn(0L, durationMs)
        }

    val fraction: Float get() = positionMs.toFloat() / durationMs.coerceAtLeast(1)

    fun sync(progressMs: Int, isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // a poll that left before our seek would snap the needle back
        if (now - seekedAt < 2500) { playing = isPlaying; return }
        anchorMs = progressMs.toLong()
        anchorAt = now
        clock = now
        playing = isPlaying
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, durationMs)
        preview = null
        anchorMs = target
        anchorAt = SystemClock.elapsedRealtime()
        clock = anchorAt
        seekedAt = anchorAt
        PlayerHub.seek(target.toInt())
    }
}

@Composable
fun rememberLiveProgress(): LiveProgress {
    val state by PlayerHub.playback.collectAsState()
    val rate by PlayerHub.rate.collectAsState()
    val localId by PlayerHub.localDeviceId.collectAsState()
    val live = remember { LiveProgress() }
    val s = state
    live.durationMs = (s?.track?.durationMs ?: 1).toLong().coerceAtLeast(1)
    val reversed by PlayerHub.reverseOn.collectAsState()
    live.speed = when {
        reversed -> -rate
        localId != null && s?.deviceId == localId -> rate
        else -> 1f
    }
    LaunchedEffect(s?.progressMs, s?.isPlaying, s?.track?.id) {
        live.sync(s?.progressMs ?: 0, s?.isPlaying == true)
    }
    LaunchedEffect(live.playing) {
        while (live.playing) withFrameNanos { live.clock = SystemClock.elapsedRealtime() }
    }
    return live
}

/**
 * A spinning platter with inertia: spins up when playback starts, coasts down
 * when it stops, and can be grabbed by a finger (then it follows the finger).
 */
@Stable
class Platter {
    var angle by mutableFloatStateOf(0f)
    var spin by mutableFloatStateOf(0f)
    var held by mutableStateOf(false)
}

@Composable
fun rememberPlatter(playing: Boolean, degPerSec: Float, rate: Float, spinUpSec: Float = 0.45f, spinDownSec: Float = 1.1f): Platter {
    val p = remember { Platter() }
    val isPlaying by rememberUpdatedState(playing)
    val reversed by PlayerHub.reverseOn.collectAsState()
    // in reverse mode every platter and reel turns the other way
    val r by rememberUpdatedState(if (reversed) -rate else rate)
    val dps by rememberUpdatedState(degPerSec)
    LaunchedEffect(playing) {
        var last = 0L
        while (isPlaying || p.spin > 0.002f) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f)
                last = t
                val target = if (isPlaying && !p.held) 1f else 0f
                val tau = if (target > p.spin) spinUpSec else spinDownSec
                p.spin += (target - p.spin) * (1f - exp(-dt / tau))
                if (!p.held) p.angle = (p.angle + dps * r * p.spin * dt) % 360f
            }
        }
        p.spin = 0f
    }
    return p
}

/** Signed angle change in degrees between two touch points around [c], wrapped to ±180. */
fun angleDelta(c: Offset, from: Offset, to: Offset): Float {
    val a = atan2(from.y - c.y, from.x - c.x)
    val b = atan2(to.y - c.y, to.x - c.x)
    var d = Math.toDegrees((b - a).toDouble()).toFloat()
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

fun angleOf(c: Offset, p: Offset): Float =
    Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()

/** The cover as a plain bitmap painter, so Canvas skins can draw it reliably. */
@Composable
fun rememberArtPainter(url: String?): Painter {
    val ctx = LocalContext.current
    var painter by remember { mutableStateOf<Painter>(ColorPainter(Color.Transparent)) }
    LaunchedEffect(url) {
        if (url == null) { painter = ColorPainter(Color.Transparent); return@LaunchedEffect }
        val req = ImageRequest.Builder(ctx).data(url).size(640).allowHardware(false).build()
        val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
        if (bmp != null) painter = BitmapPainter(bmp.asImageBitmap())
    }
    return painter
}

/**
 * A pastel accent pulled from the cover, like the reference player does:
 * the most common saturated hue, lifted to a soft, readable tint.
 */
@Composable
fun rememberArtAccent(url: String?, fallback: Color = Color(0xFFE8A48F)): Color {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url == null) { picked = fallback; return@LaunchedEffect }
        val req = ImageRequest.Builder(ctx).data(url).size(48).allowHardware(false).build()
        val bmp = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap(48, 48, Bitmap.Config.ARGB_8888)
        picked = bmp?.let { dominantPastel(it) } ?: fallback
    }
    val animated by animateColorAsState(picked, tween(700), label = "accent")
    return animated
}

private fun dominantPastel(bmp: Bitmap): Color? {
    val bins = FloatArray(24); val hueSum = FloatArray(24)
    val hsl = FloatArray(3)
    for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
        ColorUtils.colorToHSL(bmp.getPixel(x, y), hsl)
        if (hsl[2] < 0.12f || hsl[2] > 0.92f || hsl[1] < 0.18f) continue
        val w = hsl[1] * (1f - kotlin.math.abs(hsl[2] - 0.5f))
        val b = (hsl[0] / 15f).toInt().coerceIn(0, 23)
        bins[b] += w; hueSum[b] += hsl[0] * w
    }
    val best = bins.indices.maxByOrNull { bins[it] } ?: return null
    if (bins[best] < 1.5f) return null
    val hue = hueSum[best] / bins[best]
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.76f)))
}

/**
 * The thin slider from the reference: a hairline track, accent fill and a
 * small upright bar as the thumb. [onChange] fires while dragging,
 * [onDone] once on release.
 */
@Composable
fun PillSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    track: Color = Color.White.copy(alpha = 0.22f),
    label: String = "",
    stateText: String? = null,
) {
    val latest by rememberUpdatedState(value)
    var drag by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(maxOf(height, 48.dp))
            .semantics {
                contentDescription = label
                stateDescription = stateText ?: "${(latest * 100).roundToInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(latest.coerceIn(0f, 1f), 0f..1f, 20)
                setProgress { v -> onDone(v.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val v = (o.x / size.width).coerceIn(0f, 1f)
                    onChange(v); onDone(v)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onChange(drag) },
                    onDragEnd = { onDone(drag) },
                    onDragCancel = { onDone(latest) },
                ) { change, dx ->
                    change.consume()
                    drag = (drag + dx / size.width).coerceIn(0f, 1f)
                    onChange(drag)
                }
            }
    ) {
        val y = size.height / 2
        val v = value.coerceIn(0f, 1f)
        val x = v * size.width
        drawLine(track, Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
        val tw = 4.dp.toPx(); val th = 14.dp.toPx()
        drawRoundRect(
            accent, Offset(x - tw / 2, y - th / 2), Size(tw, th), CornerRadius(tw / 2)
        )
    }
}

const val TAU = (2 * PI).toFloat()

/**
 * TalkBack view of a playback stage drawn on a Canvas: what it is, where the
 * track is, double-tap to play/pause, swipe up/down (adjust) to seek, plus
 * named actions for the extra controls a skin has.
 */
fun Modifier.stageSemantics(
    what: String,
    live: LiveProgress,
    extra: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
): Modifier = semantics {
    contentDescription = what
    stateDescription = (if (live.playing) "Playing, " else "Paused, ") +
        "${com.slowva.player.ui.formatMs(live.positionMs.toInt())} of ${com.slowva.player.ui.formatMs(live.durationMs.toInt())}"
    progressBarRangeInfo = ProgressBarRangeInfo(live.fraction.coerceIn(0f, 1f), 0f..1f)
    setProgress { v -> live.seekTo((v * live.durationMs).toLong()); true }
    onClick(label = if (live.playing) "pause" else "play") { PlayerHub.togglePlay(); true }
    customActions = listOf(
        androidx.compose.ui.semantics.CustomAccessibilityAction("Back 15 seconds") { live.seekTo(live.positionMs - 15_000); true },
        androidx.compose.ui.semantics.CustomAccessibilityAction("Forward 15 seconds") { live.seekTo(live.positionMs + 15_000); true },
    ) + extra
}
