package my.umarh.paudio.designsystem.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
    val leftName = stringResource(R.string.ds_ear_left)
    val rightName = stringResource(R.string.ds_ear_right)
    val khz = stringResource(R.string.ds_axis_frequency_hz)
    val gain = stringResource(R.string.ds_axis_gain_db)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.8f)
                .semantics { contentDescription = description },
        ) {
            val l = 30.dp.toPx()
            val t = 14.dp.toPx()
            val r = size.width - 8.dp.toPx()
            val b = size.height - 32.dp.toPx()
            fun x(hz: Double) = l + ChartMath.eqXFraction(hz).coerceIn(0f, 1f) * (r - l)
            fun y(db: Double) = t + ChartMath.eqYFraction(db.coerceIn(-15.0, 15.0)) * (b - t)

            for (db in listOf(-15, -10, -5, 0, 5, 10, 15)) {
                val yy = y(db.toDouble())
                drawLine(if (db == 0) zeroLine else grid, Offset(l, yy), Offset(r, yy), if (db == 0) 1.dp.toPx() else 0.5.dp.toPx())
                if (db % 5 == 0) {
                    val layout = measurer.measure(db.toString(), labelStyle)
                    drawText(layout, topLeft = Offset(l - 4.dp.toPx() - layout.size.width, yy - layout.size.height / 2f))
                }
            }
            for (hz in EqTicksHz) {
                val xx = x(hz.toDouble())
                drawLine(grid, Offset(xx, t), Offset(xx, b), 0.5.dp.toPx())
                val text = if (hz >= 1000) formatNumber(hz / 1000.0, locale) + "k" else hz.toString()
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(xx - layout.size.width / 2f, b + 3.dp.toPx()))
            }
            val axisLayout = measurer.measure(khz, labelStyle)
            drawText(axisLayout, topLeft = Offset((l + r) / 2f - axisLayout.size.width / 2f, b + 17.dp.toPx()))
            val gainLayout = measurer.measure(gain, labelStyle)
            drawText(gainLayout, topLeft = Offset(2.dp.toPx(), 0f))

            fun curve(db: DoubleArray?, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
                val path = Path()
                var started = false
                for (i in gridHz.indices) {
                    val g = (db?.getOrNull(i) ?: 0.0) * factor
                    val px = x(gridHz[i])
                    val py = y(g)
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(
                    path, color,
                    style = Stroke(
                        2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null,
                    ),
                )
            }
            curve(rightDb, rightColor, dashed = true)
            curve(leftDb, leftColor, dashed = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("— $leftName", color = leftColor, style = MaterialTheme.typography.labelLarge)
            Text("- - $rightName", color = rightColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}
