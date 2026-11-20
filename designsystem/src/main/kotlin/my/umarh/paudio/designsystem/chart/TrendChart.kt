package my.umarh.paudio.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.umarh.paudio.designsystem.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.max

/** One test in the trend: PTA3 (mean HL_est at 1/2/4 kHz) per ear; null when that ear has no value. */
@Immutable
data class TrendPoint(val dateMillis: Long, val leftPta: Float?, val rightPta: Float?)

/**
 * PTA3 per ear over time (F13). Right ear ○, left ear ✕ (plus legend). The caller normally shows it only with
 * >= 2 tests; with fewer than 2 points an empty-state text is drawn instead. Y axis increases downward like the audiogram.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Text(
            stringResource(R.string.ds_trend_empty),
            modifier = modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sorted = points.sortedBy { it.dateMillis }
    val scheme = MaterialTheme.colorScheme
    val ears = earColors()
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val zone = remember { ZoneId.systemDefault() }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)

    val leftName = stringResource(R.string.ds_ear_left)
    val rightName = stringResource(R.string.ds_ear_right)
    val latest = sorted.last()
    val description = buildString {
        append(stringResource(R.string.ds_trend_description, sorted.size))
        latest.rightPta?.let { append(' ').append(stringResource(R.string.ds_trend_latest, rightName, formatNumber(it.toDouble(), locale))) }
        latest.leftPta?.let { append(' ').append(stringResource(R.string.ds_trend_latest, leftName, formatNumber(it.toDouble(), locale))) }
    }

    val minDb = -10f
    val maxData = sorted.maxOf { max(it.leftPta ?: Float.NEGATIVE_INFINITY, it.rightPta ?: Float.NEGATIVE_INFINITY) }
    val maxDb = max(40f, ceil((maxData + 5f) / 10f) * 10f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.8f)
                .semantics { contentDescription = description },
        ) {
            val l = 34.dp.toPx()
            val t = 8.dp.toPx()
            val r = size.width - 16.dp.toPx()
            val b = size.height - 24.dp.toPx()
            val t0 = sorted.first().dateMillis
            val span = (sorted.last().dateMillis - t0).coerceAtLeast(1L)
            fun x(ms: Long) = l + (ms - t0).toFloat() / span * (r - l)
            fun y(db: Float) = t + (db.coerceIn(minDb, maxDb) - minDb) / (maxDb - minDb) * (b - t)

            var db = 0
            while (db <= maxDb) {
                val yy = y(db.toFloat())
                drawLine(grid, Offset(l, yy), Offset(r, yy), 0.5.dp.toPx())
                val layout = measurer.measure(db.toString(), labelStyle)
                drawText(layout, topLeft = Offset(l - 4.dp.toPx() - layout.size.width, yy - layout.size.height / 2f))
                db += 10
            }
            // Date labels: first and last (and the middle one if there is room).
            val labelled = if (sorted.size > 2) listOf(sorted.first(), sorted[sorted.size / 2], sorted.last()) else sorted
            labelled.distinctBy { it.dateMillis }.forEachIndexed { idx, p ->
                val text = formatter.format(Instant.ofEpochMilli(p.dateMillis).atZone(zone).toLocalDate())
                val layout = measurer.measure(text, labelStyle)
                val xx = x(p.dateMillis)
                val left = when {
                    p === labelled.first() -> xx
                    p === labelled.last() -> xx - layout.size.width
                    else -> xx - layout.size.width / 2f
                }
                drawText(layout, topLeft = Offset(left.coerceIn(0f, size.width - layout.size.width), b + 4.dp.toPx()))
            }
            val w = 2.dp.toPx()
            val rad = 5.dp.toPx()
            fun series(values: List<Pair<Long, Float>>, color: androidx.compose.ui.graphics.Color, ring: Boolean) {
                val centers = values.map { Offset(x(it.first), y(it.second)) }
                for (i in 0 until centers.size - 1) drawLine(color.copy(alpha = 0.7f), centers[i], centers[i + 1], 1.5.dp.toPx())
                centers.forEach { if (ring) drawRingMarker(it, rad, color, w) else drawCrossMarker(it, rad * 0.85f, color, w) }
            }
            series(sorted.mapNotNull { p -> p.rightPta?.let { p.dateMillis to it } }, ears.right, ring = true)
            series(sorted.mapNotNull { p -> p.leftPta?.let { p.dateMillis to it } }, ears.left, ring = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.ds_legend_right_ear), color = ears.right, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.ds_legend_left_ear), color = ears.left, style = MaterialTheme.typography.labelLarge)
        }
    }
}
