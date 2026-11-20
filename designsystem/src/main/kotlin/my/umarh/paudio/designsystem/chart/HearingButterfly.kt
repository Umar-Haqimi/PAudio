package my.umarh.paudio.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
    val progress = remember { Animatable(0f) }
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
    val oneKLabel = stringResource(R.string.ds_khz_value, "1")
    val topLabel = frequenciesHz.maxOrNull()?.let {
        stringResource(R.string.ds_khz_value, formatNumber(it / 1000.0, locale))
    } ?: ""
    val sizeModifier = if (mini) Modifier.size(88.dp, 112.dp) else Modifier.defaultMinSize(200.dp, 240.dp)

    val leftFill = scheme.primaryContainer.copy(alpha = 0.7f)
    val leftLine = scheme.primary
    val rightFill = scheme.tertiaryContainer.copy(alpha = 0.7f)
    val rightLine = scheme.tertiary
    val axis = scheme.outlineVariant

    Canvas(
        modifier
            .then(sizeModifier)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val padV = (if (mini) 6.dp else 20.dp).toPx()
        val padH = (if (mini) 4.dp else 28.dp).toPx()
        val cx = size.width / 2f
        val top = padV
        val bottom = size.height - padV
        val halfW = (size.width / 2f - padH).coerceAtLeast(1f)
        val axisStroke = 1.dp.toPx()

        drawLine(axis, Offset(cx, top), Offset(cx, bottom), axisStroke)
        if (!mini) {
            drawText(measurer, oneKLabel, Offset(2.dp.toPx(), bottom - 6.dp.toPx()), labelStyle)
            drawText(measurer, topLabel, Offset(2.dp.toPx(), top - 6.dp.toPx()), labelStyle)
        }
        val p = progress.value
        drawWing(
            hl = leftHl, freqs = frequenciesHz, censored = censoredLeft, sign = -1, cx = cx, halfW = halfW,
            top = top, bottom = bottom, fill = leftFill, line = leftLine, progress = p, mini = mini,
        )
        drawWing(
            hl = rightHl, freqs = frequenciesHz, censored = censoredRight, sign = 1, cx = cx, halfW = halfW,
            top = top, bottom = bottom, fill = rightFill, line = rightLine, progress = p, mini = mini,
        )
    }
}

private fun DrawScope.drawWing(
    hl: List<Float?>,
    freqs: List<Int>,
    censored: List<Boolean>,
    sign: Int,
    cx: Float,
    halfW: Float,
    top: Float,
    bottom: Float,
    fill: Color,
    line: Color,
    progress: Float,
    mini: Boolean,
) {
    val pts = ArrayList<Offset>()
    val censoredPts = ArrayList<Offset>()
    for (i in freqs.indices) {
        val v = hl.getOrNull(i) ?: continue
        val y = bottom - ChartMath.butterflyHeightFraction(freqs[i].toFloat()) * (bottom - top)
        val x = cx + sign * ChartMath.butterflyHalfWidthFraction(v) * halfW
        pts += Offset(x, y)
        if (censored.getOrNull(i) == true) censoredPts += Offset(x, y)
    }
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(cx, pts.first().y)
        lineTo(pts.first().x, pts.first().y)
        catmullRomThrough(pts, moveToFirst = false)
        lineTo(cx, pts.last().y)
        close()
    }
    drawPath(path, fill.copy(alpha = fill.alpha * progress))
    val strokeW = (if (mini) 1.5.dp else 2.dp).toPx()
    val measure = PathMeasure().apply { setPath(path, false) }
    val trimmed = Path()
    measure.getSegment(0f, measure.length * progress, trimmed, true)
    drawPath(trimmed, line, style = Stroke(strokeW))
    if (!mini && progress >= 1f) {
        censoredPts.forEach { drawCircle(line, 4.dp.toPx(), it, style = Stroke(1.5.dp.toPx())) }
    }
}
