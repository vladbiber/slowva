@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slowva.player.ui.theme

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

// Material 3 Expressive shapes, as used by Levyra: cookies for artists, stars and clovers for hero art,
// plain rounded squares (MaterialTheme.shapes) for song covers in lists.

/** Artist photos and collages. */
val ArtistShape: Shape by lazy { PolygonShape(MaterialShapes.Cookie9Sided) }

/** The big album cover (player, album and playlist headers). */
val HeroCoverShape: Shape by lazy { PolygonShape(MaterialShapes.Cookie12Sided) }

/** The play button at rest. */
val PlayShape: Shape by lazy { PolygonShape(MaterialShapes.Sunny) }

/** Home playlist and album tiles rotate through these; see [tileShape]. */
val TileShapes: List<Shape> by lazy {
    listOf(MaterialShapes.Cookie4Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.SoftBurst, MaterialShapes.Cookie6Sided)
        .map { PolygonShape(it) }
}

/** The same tile shape for the same name, every time. */
fun tileShape(name: String): Shape = TileShapes[tileIndex(name, TileShapes.size)]

internal fun tileIndex(name: String, count: Int): Int = (name.lowercase().hashCode() and 0x7fffffff) % count

/** A polygon stretched to fill the layout box. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    private val bounds = polygon.calculateBounds().let { RectF(it[0], it[1], it[2], it[3]) }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath(android.graphics.Path())
        path.transform(fit(bounds, size))
        return Outline.Generic(path.asComposePath())
    }
}

/** A [Morph] frozen at [progress] (0 = start polygon, 1 = end), sized like [PolygonShape]. */
class MorphShape(private val morph: Morph, private val progress: Float, start: RoundedPolygon, end: RoundedPolygon) : Shape {
    private val bounds = RectF().apply {
        val a = start.calculateBounds()
        val b = end.calculateBounds()
        set(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, android.graphics.Path())
        path.transform(fit(bounds, size))
        return Outline.Generic(path.asComposePath())
    }

    override fun equals(other: Any?) = other is MorphShape && other.morph === morph && other.progress == progress
    override fun hashCode() = morph.hashCode() * 31 + progress.hashCode()
}

private fun fit(bounds: RectF, size: Size) = Matrix().apply {
    setRectToRect(bounds, RectF(0f, 0f, size.width, size.height), Matrix.ScaleToFit.FILL)
}

/** The play button's star and the circle it presses into. */
internal object PlayMorph {
    val rest: RoundedPolygon get() = MaterialShapes.Sunny
    val pressed: RoundedPolygon get() = MaterialShapes.Circle
    val morph: Morph by lazy { Morph(rest, pressed) }
}
