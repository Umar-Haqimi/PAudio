package my.umarh.paudio.designsystem.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.umarh.paudio.designsystem.R

internal val EqTicksHz = listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
internal val EqGainTicksDb = listOf(-15, -10, -5, 0, 5, 10, 15)

/**
 * Live EQ graph (F11): 20 Hz-20 kHz log x, +-15 dB y. Both ears are drawn (left = solid + legend, right = dashed + legend).
 * When [personalized] turns false the curves spring to flat (0 dB); when it turns true they spring back to G(f).
 *
 * @param leftDb / rightDb gain in dB per entry of [gridHz]; null = flat for that ear.
 */
@Composable
fun EqGraph(
    leftDb: DoubleArray?,
    rightDb: DoubleArray?,
    gridHz: DoubleArray,
    personalized: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val factor by animateFloatAsState(
        targetValue = if (personalized) 1f else 0f,
        animationSpec = if (reduced) androidx.compose.animation.core.snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "eqPersonalized",
    )
    val leftColor = scheme.primary
    val rightColor = scheme.tertiary
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)
    val zeroLine = scheme.outline
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)

    val peak = { a: DoubleArray? -> a?.maxOrNull()?.coerceIn(-15.0, 15.0) ?: 0.0 }
    val description = if (personalized) {
        stringResource(
            R.string.ds_eq_description_personalized,
            formatNumber(peak(leftDb), locale), formatNumber(peak(rightDb), locale),
        )
    } else {
        stringResource(R.string.ds_eq_description_original)
    }
    val khzShort = stringResource(R.string.ds_khz_short, "%1\$s")
    val leftName = stringResource(R.string.ds_ear_left)
    val rightName = stringResource(R.string.ds_ear_right)
    val khz = stringResource(R.string.ds_axis_frequency_hz)
    val gain = stringResource(R.string.ds_axis_gain_db)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.8f)
                .semantics { contentDescription = description }
                // Geometry, grid and text layouts are built once per size/input change; only the two curves are
                // redrawn per animation frame (the spring reads `factor` in the draw phase, never in composition).
                .drawWithCache {
                    val l = 30.dp.toPx()
                    val t = 14.dp.toPx()
                    val r = size.width - 8.dp.toPx()
                    val b = size.height - 32.dp.toPx()
                    fun x(hz: Double) = l + ChartMath.eqXFraction(hz).coerceIn(0f, 1f) * (r - l)
                    fun y(db: Double) = t + ChartMath.eqYFraction(db.coerceIn(-15.0, 15.0)) * (b - t)

                    val hairline = 0.5.dp.toPx()
                    val zeroW = 1.dp.toPx()
                    val yLabels = EqGainTicksDb.map { db ->
                        val layout = measurer.measure(db.toString(), labelStyle)
                        Triple(y(db.toDouble()), db, layout)
                    }
                    val xLabels = EqTicksHz.map { hz ->
                        val text = if (hz >= 1000) String.format(locale, khzShort, formatNumber(hz / 1000.0, locale)) else hz.toString()
                        Pair(x(hz.toDouble()), measurer.measure(text, labelStyle))
                    }
                    val axisLayout = measurer.measure(khz, labelStyle)
                    val gainLayout = measurer.measure(gain, labelStyle)
                    val xs = FloatArray(gridHz.size) { x(gridHz[it]) }
                    val strokeW = 2.5.dp.toPx()
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                    val path = Path()

                    fun DrawScope.curve(db: DoubleArray?, color: androidx.compose.ui.graphics.Color, dashed: Boolean, f: Float) {
                        path.reset()
                        for (i in xs.indices) {
                            val py = y((db?.getOrNull(i) ?: 0.0) * f)
                            if (i == 0) path.moveTo(xs[i], py) else path.lineTo(xs[i], py)
                        }
                        drawPath(
                            path, color,
                            style = Stroke(
                                strokeW,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                pathEffect = if (dashed) dash else null,
                            ),
                        )
                    }

                    onDrawBehind {
                        for ((yy, db, layout) in yLabels) {
                            drawLine(if (db == 0) zeroLine else grid, Offset(l, yy), Offset(r, yy), if (db == 0) zeroW else hairline)
                            drawText(layout, topLeft = Offset(l - 4.dp.toPx() - layout.size.width, yy - layout.size.height / 2f))
                        }
                        for ((xx, layout) in xLabels) {
                            drawLine(grid, Offset(xx, t), Offset(xx, b), hairline)
                            drawText(layout, topLeft = Offset(xx - layout.size.width / 2f, b + 3.dp.toPx()))
                        }
                        drawText(axisLayout, topLeft = Offset((l + r) / 2f - axisLayout.size.width / 2f, b + 17.dp.toPx()))
                        drawText(gainLayout, topLeft = Offset(2.dp.toPx(), 0f))
                        val f = factor // draw-phase state read
                        curve(rightDb, rightColor, dashed = true, f = f)
                        curve(leftDb, leftColor, dashed = false, f = f)
                    }
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ds_eq_legend_left, leftName), color = leftColor, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.ds_eq_legend_right, rightName), color = rightColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}
