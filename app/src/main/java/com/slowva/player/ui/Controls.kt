package com.slowva.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs

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
        )
    }
}
