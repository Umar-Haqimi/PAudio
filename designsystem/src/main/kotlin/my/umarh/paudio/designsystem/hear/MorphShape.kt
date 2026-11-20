package my.umarh.paudio.designsystem.hear

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph

/**
 * Compose [Shape] drawing a graphics-shapes [Morph] at [progress] (0 = start polygon, 1 = end polygon; springs may
 * overshoot). Built on the stable `Morph.asCubics`, not on the experimental M3 `toPath`, and not on foundation's
 * `MorphPolygonShape` (which cannot take a `RoundedPolygon`). MaterialShapes polygons are normalised to the unit
 * square, so the path is simply scaled to the size.
 */
class MorphShape(private val morph: Morph, private val progress: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(morph.asCubics(progress()).toPath(size))
}

internal fun List<Cubic>.toPath(size: Size): Path {
    val path = Path()
    if (isEmpty()) return path
    path.moveTo(first().anchor0X * size.width, first().anchor0Y * size.height)
    for (c in this) {
        path.cubicTo(
            c.control0X * size.width, c.control0Y * size.height,
            c.control1X * size.width, c.control1Y * size.height,
            c.anchor1X * size.width, c.anchor1Y * size.height,
        )
    }
    path.close()
    return path
}
