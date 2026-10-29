package com.slowva.player.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val STAGGER_ITEMS = 10
private const val STAGGER_STEP_MS = 35L

/** True when the system "Remove animations" setting is on (animator duration scale 0); read once per call site. */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

// MaterialTheme.motionScheme is internal in material3 1.4, so the M3 Expressive spring tokens are copied here
/** M3 Expressive default spatial spring (a little bounce): for things that move or resize. */
fun <T> standardSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

/** M3 Expressive fast effects spring (no bounce): for alpha, color and press feedback. */
fun <T> fastSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

/**
 * Fades the item in and slides it up a few dp, [index] steps after the first one.
 * Plays once: the flag is saveable, so lazy items scrolled away and back stay put.
 * Only the first [STAGGER_ITEMS] animate, so rows reached by scrolling just appear.
 */
@Composable
fun Modifier.enterStagger(index: Int): Modifier {
    if (reducedMotion() || index !in 0 until STAGGER_ITEMS) return this
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        delay(index * STAGGER_STEP_MS)
        progress.animateTo(1f, standardSpring())
        shown = true
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

/** Shrinks a card or row a little while [interactionSource] is pressed; pass the same source to its clickable. */
@Composable
fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    if (reducedMotion()) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, fastSpring(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A loading block: a light band sweeps across; a flat block when motion is reduced. Clip it first for round corners. */
@Composable
fun Modifier.shimmer(): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    if (reducedMotion()) return background(base)
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val x = rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    return drawBehind {
        val start = size.width * x.value
        drawRect(Brush.linearGradient(listOf(base, light, base), Offset(start - size.width, 0f), Offset(start, size.height)))
    }
}

/** [count] stand-ins for song or station rows while a list loads. */
@Composable
fun RowPlaceholders(count: Int = 5) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" }) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(MaterialTheme.shapes.small).shimmer())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                }
            }
        }
    }
}

/** A cover tile stand-in: [size] art in [shape] with a caption line. */
@Composable
fun TilePlaceholder(size: Dp, shape: Shape = MaterialTheme.shapes.medium) {
    Column(Modifier.width(size)) {
        Box(Modifier.size(size).clip(shape).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
    }
}

/** A Home shelf while it loads: the real [title] over a row of [TilePlaceholder]s. */
@Composable
fun ShelfPlaceholder(title: String, tile: Dp, shape: Shape = MaterialTheme.shapes.medium) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$title, loading" }) {
        SectionHeader(title, Modifier.padding(top = 10.dp, start = 4.dp))
        Spacer(Modifier.height(8.dp))
        // a disabled scroll lets the last tile run off the edge instead of being squeezed
        Row(Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(5) { TilePlaceholder(tile, shape) }
        }
    }
}
