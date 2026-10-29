package com.slowva.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slowva.player.ui.theme.MorphShape
import com.slowva.player.ui.theme.PlayMorph
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The house buttons. Rules:
 * 1. Icon buttons are 48dp touch targets with 24dp icons (20dp in dense rows). Icons take
 *    LocalContentColor; only an "on" state (a toggle, a running timer) is drawn in the accent.
 * 2. Every main play button is a [PlayPauseButton]: accent fill on a Sunny star (see ui/theme/Shapes.kt).
 * 3. [CtButton]: Primary (Filled) is the single main action of a screen or sheet, Secondary (FilledTonal)
 *    the other actions, Tertiary (Text) dismiss and minor ones. Outlined is only for a toggle that is off.
 * 4. Pressing morphs the shape (round corners tighten towards a squircle, the play star turns round)
 *    and dips the button a little. With "Remove animations" on it simply snaps.
 */

enum class Emphasis { Primary, Secondary, Tertiary }

/** A text button in one of the three [Emphasis] levels. */
@Composable
fun CtButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: Emphasis = Emphasis.Secondary,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val (shape, scale) = pressMorph(src, rest = 50, pressed = 22, pressedScale = 0.96f)
    val m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    when (emphasis) {
        Emphasis.Primary -> Button(onClick, m, enabled, shape = shape, interactionSource = src, content = content)
        Emphasis.Secondary -> FilledTonalButton(onClick, m, enabled, shape = shape, interactionSource = src, content = content)
        Emphasis.Tertiary -> TextButton(onClick, m, enabled, shape = shape, interactionSource = src, content = content)
    }
}

/** A 48dp icon button; [tint] stays the content color unless the icon shows an "on" state. */
@Composable
fun CtIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dense: Boolean = false,
    tint: Color = LocalContentColor.current,
) {
    val src = remember { MutableInteractionSource() }
    val (shape, scale) = pressMorph(src)
    IconButton(
        onClick, modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint), interactionSource = src, shape = shape,
    ) {
        Icon(icon, contentDescription, Modifier.size(if (dense) 20.dp else 24.dp))
    }
}

/**
 * A 48dp on/off icon button. On, it is drawn in [accent] on a soft accent container; with [pop]
 * (the heart) there is no container: turning it on bounces the icon with a small burst, turning it off fades.
 */
@Composable
fun CtToggleIconButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    contentDescription: String,
    accent: Color,
    modifier: Modifier = Modifier,
    checkedIcon: ImageVector = icon,
    enabled: Boolean = true,
    dense: Boolean = false,
    pop: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val (shape, scale) = pressMorph(src)
    val scope = rememberCoroutineScope()
    val motion = !reducedMotion()
    val bump = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val fill by animateFloatAsState(if (checked) 1f else 0f, tween(220), label = "fill")
    val ink = LocalContentColor.current
    IconToggleButton(
        checked,
        onCheckedChange = { on ->
            onCheckedChange(on)
            // only a tap pops, so moving to an already liked song stays calm
            if (pop && on && motion) {
                scope.launch { bump.snapTo(0.55f); bump.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium)) }
                scope.launch { burst.snapTo(0f); burst.animateTo(1f, tween(420)) }
            }
        },
        modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled,
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = ink,
            checkedContentColor = accent,
            checkedContainerColor = if (pop) Color.Transparent else accent.copy(alpha = 0.2f),
        ),
        interactionSource = src,
        shape = shape,
    ) {
        val iconSize = if (dense) 20.dp else 24.dp
        if (!pop) Icon(if (checked) checkedIcon else icon, contentDescription, Modifier.size(iconSize))
        else Box(
            Modifier.size(iconSize).drawBehind {
                val t = burst.value
                if (t < 1f) for (i in 0 until 6) {
                    val a = i * (Math.PI / 3).toFloat() - (Math.PI / 2).toFloat()
                    val r = size.minDimension * (0.5f + 0.35f * t)
                    drawCircle(accent.copy(alpha = 1f - t), 2.dp.toPx() * (1f - t * 0.5f), center + Offset(cos(a) * r, sin(a) * r))
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val layer = Modifier.size(iconSize).graphicsLayer { scaleX = bump.value; scaleY = bump.value }
            Icon(icon, contentDescription, layer.graphicsLayer { alpha = 1f - fill }, tint = ink)
            Icon(checkedIcon, null, layer.graphicsLayer { alpha = fill }, tint = accent)
        }
    }
}

/**
 * The main play button: [accent] (or [brush]) fill on a Sunny star that softens while playing and
 * morphs into a circle while pressed. Size it with [modifier]; at least 48dp.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    iconSize: Dp = 28.dp,
) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val motion = !reducedMotion()
    val target = playMorph(playing, down)
    val progress by animateFloatAsState(target, if (motion) spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow) else snap(), label = "morph")
    val scale by animateFloatAsState(if (down) 0.92f else 1f, spring(dampingRatio = 0.5f), label = "scale")
    val shape = remember(progress) { MorphShape(PlayMorph.morph, progress.coerceIn(0f, 1f), PlayMorph.rest, PlayMorph.pressed) }
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(brush ?: SolidColor(accent), shape)
            .clickable(src, ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = if (brush != null) MaterialTheme.colorScheme.onPrimary else if (accent.luminance() > 0.4f) Color(0xFF1A1719) else Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** How far the play star has morphed towards a circle: a full star paused, softer playing, round pressed. */
internal fun playMorph(playing: Boolean, pressed: Boolean): Float = when {
    pressed -> 1f
    playing -> 0.4f
    else -> 0f
}

/** Shape and scale that follow a press: [rest] and [pressed] are corner percents. */
@Composable
private fun pressMorph(src: InteractionSource, rest: Int = 50, pressed: Int = 28, pressedScale: Float = 0.9f): Pair<Shape, Float> {
    val down by src.collectIsPressedAsState()
    val corner by animateFloatAsState(if (down) pressed.toFloat() else rest.toFloat(), spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "corner")
    val scale by animateFloatAsState(if (down) pressedScale else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "scale")
    return RoundedCornerShape(corner.roundToInt()) to scale
}

/** Scrolls one-line text that does not fit; with animations off it stays put (keep an ellipsis overflow). */
@Composable
fun Modifier.marquee(): Modifier = if (reducedMotion()) this else basicMarquee()
