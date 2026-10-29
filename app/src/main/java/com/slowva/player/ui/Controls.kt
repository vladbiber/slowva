package com.slowva.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.slowva.player.ui.skins.TAU
import kotlin.math.abs
import kotlin.math.exp

/**
 * A slider with a tick where the default sits, and a small magnet that snaps
 * onto it, otherwise getting back to exactly 1.00x by dragging is luck.
 */
@Composable
fun MarkedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    default: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    stateText: String? = null,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((default - valueRange.start) / span).coerceIn(0f, 1f)
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            // the track stops half a thumb short of each edge
            val inset = 10.dp.toPx()
            val x = inset + fraction * (size.width - 2 * inset)
            val half = 5.dp.toPx()
            drawLine(
                color = markColor,
                start = Offset(x, center.y - half),
                end = Offset(x, center.y + half),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Slider(
            enabled = enabled,
            modifier = Modifier.semantics {
                description?.let { contentDescription = it }
                stateText?.let { stateDescription = it }
            },
            value = value.coerceIn(valueRange),
            onValueChange = { raw ->
                onValueChange(if (abs(raw - default) < span * 0.02f) default else raw)
            },
            valueRange = valueRange,
            colors = fxSliderColors(),
        )
    }
}

/** One slider color everywhere in the sound sheet: primary on a faint primary track. */
@Composable
fun fxSliderColors(): SliderColors {
    val c = MaterialTheme.colorScheme
    return SliderDefaults.colors(
        thumbColor = c.primary, activeTrackColor = c.primary, activeTickColor = c.onPrimary,
        inactiveTrackColor = c.primary.copy(alpha = 0.22f), inactiveTickColor = c.primary,
    )
}

/** The seek bar's wave: [amp] 0 (flat) to 1, [phase] in radians. Read them in the draw phase only. */
@Stable
class Wave {
    var amp by mutableFloatStateOf(0f)
    var phase by mutableFloatStateOf(0f)
}

/** A wave that swells while [on] (playing and not dragged) and flattens smoothly otherwise; flat with animations off. */
@Composable
fun rememberWave(on: Boolean): Wave {
    val w = remember { Wave() }
    val live = on && !reducedMotion()
    LaunchedEffect(live) {
        var last = 0L
        while (live || w.amp > 0.002f) withFrameNanos { t ->
            val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f)
            last = t
            w.amp = easeToward(w.amp, if (live) 1f else 0f, dt, 0.18f)
            w.phase = (w.phase + dt * TAU * 0.9f) % TAU
        }
        w.amp = 0f
    }
    return w
}

/** One frame of exponential easing from [from] to [to] with time constant [tau] seconds; never overshoots. */
internal fun easeToward(from: Float, to: Float, dt: Float, tau: Float): Float = from + (to - from) * (1f - exp(-dt / tau))
