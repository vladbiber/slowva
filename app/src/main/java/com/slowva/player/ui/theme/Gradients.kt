package com.slowva.player.ui.theme

import com.slowva.player.player.fx.Moods
import com.slowva.player.player.fx.Mood
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.slowva.player.player.PlayerHub
import com.slowva.player.ui.reducedMotion
import kotlin.math.abs

/** Color 1 fading into color 2 with Two colors on, else solid primary: for play button, selected tab, progress, sliders. */
@Composable
fun accentBrush(): Brush {
    val source by Appearance.source.collectAsState()
    val s = MaterialTheme.colorScheme
    return if (source == ThemeSource.DUO) Brush.linearGradient(listOf(s.primary, s.tertiary)) else SolidColor(s.primary)
}

/**
 * The backdrop behind big surfaces (player, Home header), styled by [Appearance.gradient]: a tie-dye (Android 13+,
 * older phones get Fade), the soft two-color Fade, or nothing when Off.
 * [coverColors] are the album cover's colors for Dye colors "Album cover"; theme colors when null.
 * [fadeOut] fades the dye into the background by that fraction of the height; null covers it all,
 * with a [bottomScrim] (background alpha at the bottom edge) so the controls stay readable.
 * With a [pulse] (see [rememberMusicPulse]) the dye deepens and swells with the bass.
 */
@Composable
fun LiquidBackground(
    modifier: Modifier = Modifier,
    pulse: State<Float>? = null,
    coverColors: List<Color>? = null,
    fadeOut: Float? = null,
    bottomScrim: Float = if (fadeOut == null) 0.45f else 0f,
    coverBehind: Boolean = true,
) {
    val style by Appearance.gradient.collectAsState()
    if (style == GradientStyle.OFF) return
    // the player's blurred cover shows through; places without a cover get the fade
    if (style == GradientStyle.ALBUM && coverBehind) return
    val source by Appearance.dyeColors.collectAsState()
    val strength by Appearance.dyeStrength.collectAsState()
    val s = MaterialTheme.colorScheme
    val mood by Moods.mood.collectAsState()
    val dyes = remember(source, s.primary, s.tertiary, coverColors, mood) {
        MoodDyes[mood] ?: dyeSet(source, s.primary, s.tertiary, coverColors)
    }
    // a bad shader must not crash the app: it just falls back to Fade
    val dye = if (style.dyed && Build.VERSION.SDK_INT >= 33) remember { runCatching { TieDyeBrush() }.getOrNull() } else null
    val flow = if (dye != null && !reducedMotion()) {
        rememberInfiniteTransition(label = "dye")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(DYE_LOOP_MS, easing = LinearEasing)), label = "flow")
    } else null
    val scrim = s.background
    val one = if (pulse != null) s.primary.boosted() else s.primary
    val two = if (pulse != null) s.tertiary.boosted() else s.tertiary
    val masked = dye == null && fadeOut != null
    Canvas(if (masked) modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else modifier) {
        // read here, in the draw phase, so each beat redraws without recomposing
        val e = pulse?.value ?: 0f
        if (dye != null && Build.VERSION.SDK_INT >= 33) {
            val mode = style.ordinal.coerceAtMost(GradientStyle.CHEVRON.ordinal)
            drawRect(dye.at(size.width, size.height, flow?.value ?: 0.3f, e, strength.amount, mode, fadeOut ?: 0f, 0.94f, dyes[0], dyes[1], dyes[2]))
        } else {
            val al = if (pulse != null) (0.53f + 0.18f * e).coerceAtMost(0.75f) else 0.38f
            drawRect(Brush.linearGradient(listOf(one.copy(alpha = al), two.copy(alpha = al))))
            if (fadeOut != null) drawRect(
                Brush.verticalGradient(0f to Color.Black, 0.25f * fadeOut to Color.Black, fadeOut to Color.Transparent, endY = size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottomScrim > 0f) drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to scrim.copy(alpha = bottomScrim), endY = size.height))
    }
}

/** Evil bleeds red into black, Godly is TempleOS yellow, blue and cyan, Good & Evil only white and greys. */
private val MoodDyes = mapOf(
    Mood.EVIL to listOf(Color(0xFFD00000), Color(0xFF5A0000), Color(0xFFFF3B1F)),
    Mood.GODLY to listOf(Color(0xFFFFFF55), Color(0xFF5555FF), Color(0xFF55FFFF)),
    Mood.GOOD_EVIL to listOf(Color(0xFFFFFFFF), Color(0xFF989898), Color(0xFFD0D0D0)),
)

/** A vivid rainbow; the dyes bleed through orange, green and violet. */
private val RainbowDyes = listOf(Color(0xFFED1F8C), Color(0xFFFFC714), Color(0xFF148CF2))

/** The three dyes: theme primary, tertiary and their hue midpoint, the rainbow, or the cover's colors. */
internal fun dyeSet(source: DyeColors, primary: Color, tertiary: Color, cover: List<Color>?): List<Color> {
    if (source == DyeColors.RAINBOW) return RainbowDyes
    val c = cover?.takeIf { source == DyeColors.COVER && it.isNotEmpty() }
    val one = (c?.get(0) ?: primary).dyeTone()
    val two = (c?.getOrNull(1) ?: if (c != null) c[0] else tertiary).dyeTone()
    val three = c?.getOrNull(2)?.dyeTone() ?: between(one, two)
    return listOf(one, two, three)
}

/** Dark-theme tones are pastel; a dye wants them saturated and bright. */
private fun Color.dyeTone(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.35f).coerceIn(0.55f, 0.95f)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** The hue halfway round the short way; close hues get pushed 50 degrees apart so three dyes show. */
private fun between(a: Color, b: Color): Color {
    val x = FloatArray(3)
    val y = FloatArray(3)
    android.graphics.Color.colorToHSV(a.toArgb(), x)
    android.graphics.Color.colorToHSV(b.toArgb(), y)
    var d = y[0] - x[0]
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    val h = if (abs(d) < 40f) x[0] + 50f else x[0] + d / 2f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf((h % 360f + 360f) % 360f, (x[1] + y[1]) / 2f, (x[2] + y[2]) / 2f)))
}

/** More saturated and a little brighter, for the music-reactive background. */
fun Color.boosted(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.45f).coerceAtMost(1f)
    hsv[2] = hsv[2].coerceAtLeast(0.55f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = alpha)
}

/**
 * Smoothed bass energy (0..1) for the player background, or null when React to music is off or
 * battery saver is on. The spectrum is only read while [playing]; on pause it fades back to 0.
 */
@Composable
fun rememberMusicPulse(playing: Boolean): State<Float>? {
    val react by Appearance.reactToMusic.collectAsState()
    val saver = rememberPowerSave()
    val level = remember { mutableFloatStateOf(0f) }
    val on = react && !saver
    LaunchedEffect(on && playing) {
        if (on && playing) PlayerHub.spectrum.collect { s ->
            val e = bassEnergy(s)
            // quick to swell, slow to settle
            level.floatValue += (e - level.floatValue) * if (e > level.floatValue) 0.5f else 0.12f
        } else while (level.floatValue > 0.01f) {
            withFrameNanos { }
            level.floatValue *= 0.9f
        }
        level.floatValue = 0f
    }
    return if (on) level else null
}

/** The lowest bands, stretched so ordinary music spans most of 0..1 instead of sitting near the top. */
internal fun bassEnergy(spectrum: FloatArray): Float {
    if (spectrum.size < 4) return 0f
    val low = (spectrum[0] + spectrum[1] + spectrum[2] + spectrum[3]) / 4f
    return ((low - 0.35f) / 0.55f).coerceIn(0f, 1f)
}

/** Battery saver, kept current by the system broadcast while on screen. */
@Composable
private fun rememberPowerSave(): Boolean {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var saver by remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { saver = pm?.isPowerSaveMode == true }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return saver
}
