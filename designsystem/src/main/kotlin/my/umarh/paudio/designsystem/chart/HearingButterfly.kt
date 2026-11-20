package my.umarh.paudio.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import my.umarh.paudio.designsystem.R

private const val DRAW_IN_MS = 900

/**
 * Hearing butterfly (F9): vertical axis = frequency (first frequency at the bottom, last at the top; log),
 * left wing = left ear, right wing = right ear, mirrored around the centre. Wing half-width at f is
 * `map(clamp(60 - HL_est, 0, 70))`, so a wider wing means a lower estimated level. A null HL skips that frequency.
 *
 * @param leftHl / rightHl HL_est per entry of [frequenciesHz] (same order), null = not measured.
 * @param censoredLeft / censoredRight optional per-frequency flags; censored points get a ring on the wing edge.
 * @param contentDescription caller-provided summary (per-ear plain-words result) read by screen readers.
 * @param animate draw-in via 900 ms path trim; skipped under reduced motion.
 * @param mini compact variant without labels (Home card). The caller sizes the full variant (default min 200x240 dp).
 */
@Composable
fun HearingButterfly(
    leftHl: List<Float?>,
    rightHl: List<Float?>,
    frequenciesHz: List<Int>,
    modifier: Modifier = Modifier,
    censoredLeft: List<Boolean> = emptyList(),
    censoredRight: List<Boolean> = emptyList(),
    contentDescription: String = stringResource(R.string.ds_butterfly_default_description),
    animate: Boolean = true,
    mini: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(leftHl, rightHl, frequenciesHz, animate, reduced) {
        if (animate && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DRAW_IN_MS, easing = LinearEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val minF = frequenciesHz.minOrNull()
    val maxF = frequenciesHz.maxOrNull()
    val minLabel = minF?.let { stringResource(R.string.ds_khz_value, formatNumber(it / 1000.0, locale)) } ?: ""
    val maxLabel = maxF?.let { stringResource(R.string.ds_khz_value, formatNumber(it / 1000.0, locale)) } ?: ""
    val sizeModifier = if (mini) Modifier.size(88.dp, 112.dp) else Modifier.defaultMinSize(200.dp, 240.dp)

    val leftFill = scheme.primaryContainer.copy(alpha = 0.7f)
    val leftLine = scheme.primary
    val rightFill = scheme.tertiaryContainer.copy(alpha = 0.7f)
    val rightLine = scheme.tertiary
    val axis = scheme.outlineVariant

    Box(
        modifier
            .then(sizeModifier)
            .semantics { this.contentDescription = contentDescription }
            .drawWithCache {
                // Geometry, paths and text layouts are rebuilt only when size or inputs change, not per frame.
                val padV = (if (mini) 6.dp else 20.dp).toPx()
                val padH = (if (mini) 4.dp else 28.dp).toPx()
                val cx = size.width / 2f
                val top = padV
                val bottom = size.height - padV
                val halfW = (size.width / 2f - padH).coerceAtLeast(1f)
                val leftPts = butterflyWingPoints(leftHl, frequenciesHz, censoredLeft, -1, cx, halfW, top, bottom)
                val rightPts = butterflyWingPoints(rightHl, frequenciesHz, censoredRight, 1, cx, halfW, top, bottom)
                val leftPath = wingPath(leftPts, cx)
                val rightPath = wingPath(rightPts, cx)
                val leftMeasure = PathMeasure().apply { setPath(leftPath, false) }
                val rightMeasure = PathMeasure().apply { setPath(rightPath, false) }
                val leftLen = leftMeasure.length
                val rightLen = rightMeasure.length
                val trimmed = Path()
                val minLayout = if (!mini && minF != null) measurer.measure(minLabel, labelStyle) else null
                val maxLayout = if (!mini && maxF != null) measurer.measure(maxLabel, labelStyle) else null
                val strokeW = (if (mini) 1.5.dp else 2.dp).toPx()
                val ringR = 4.dp.toPx()
                val ringW = 1.5.dp.toPx()
                val axisW = 1.dp.toPx()
                fun yOf(f: Int) = bottom - ChartMath.butterflyHeightFraction(f.toFloat()) * (bottom - top)
                onDrawBehind {
                    drawLine(axis, Offset(cx, top), Offset(cx, bottom), axisW)
                    minLayout?.let { drawText(it, topLeft = Offset(2.dp.toPx(), yOf(minF!!) - it.size.height / 2f)) }
                    maxLayout?.let { drawText(it, topLeft = Offset(2.dp.toPx(), yOf(maxF!!) - it.size.height / 2f)) }
                    val p = progress.value
                    fun wing(path: Path, measure: PathMeasure, len: Float, pts: List<WingPoint>, fill: Color, line: Color, hasWing: Boolean) {
                        if (!hasWing) return
                        drawPath(path, fill.copy(alpha = fill.alpha * p))
                        trimmed.reset()
                        measure.getSegment(0f, len * p, trimmed, true)
                        drawPath(trimmed, line, style = Stroke(strokeW))
                        if (!mini && p >= 1f) pts.forEach { if (it.censored) drawCircle(line, ringR, it.offset, style = Stroke(ringW)) }
                    }
                    wing(leftPath, leftMeasure, leftLen, leftPts, leftFill, leftLine, leftPts.size >= 2)
                    wing(rightPath, rightMeasure, rightLen, rightPts, rightFill, rightLine, rightPts.size >= 2)
                }
            },
    )
}

internal class WingPoint(val offset: Offset, val censored: Boolean)

/** Wing edge points for one ear; [sign] = -1 for the left wing, +1 for the right (mirrored around [cx]). */
internal fun butterflyWingPoints(
    hl: List<Float?>,
    freqs: List<Int>,
    censored: List<Boolean>,
    sign: Int,
    cx: Float,
    halfW: Float,
    top: Float,
    bottom: Float,
): List<WingPoint> {
    val pts = ArrayList<WingPoint>()
    for (i in freqs.indices) {
        val v = hl.getOrNull(i) ?: continue
        val y = bottom - ChartMath.butterflyHeightFraction(freqs[i].toFloat()) * (bottom - top)
        val x = cx + sign * ChartMath.butterflyHalfWidthFraction(v) * halfW
        pts += WingPoint(Offset(x, y), censored.getOrNull(i) == true)
    }
    return pts
}

private fun wingPath(pts: List<WingPoint>, cx: Float): Path {
    val path = Path()
    if (pts.size < 2) return path
    val o = pts.map { it.offset }
    path.apply {
        moveTo(cx, o.first().y)
        lineTo(o.first().x, o.first().y)
        catmullRomThrough(o, moveToFirst = false)
        lineTo(cx, o.last().y)
        close()
    }
    return path
}
