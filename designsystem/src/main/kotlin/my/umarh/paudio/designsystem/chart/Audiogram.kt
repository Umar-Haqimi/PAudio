package my.umarh.paudio.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.umarh.paudio.designsystem.R
import java.util.Locale

/** One estimated threshold. [hlDb] is HL_est in dB; [censored] = not heard at the loudest level (true value is higher). */
@Immutable
data class AudiogramPoint(
    val frequencyHz: Int,
    val hlDb: Float,
    val censored: Boolean = false,
    val lowConfidence: Boolean = false,
)

/** WHO 2021 hearing-grade bands (better-ear PTA, dB HL). PAudio's grade is approximate (no 0.5 kHz). */
enum class WhoBand(val fromDb: Int, val toDb: Int?) {
    Normal(-10, 20),
    Mild(20, 35),
    Moderate(35, 50),
    ModeratelySevere(50, 65),
    Severe(65, 80),
    Profound(80, 95),
    Complete(95, null),
}

@Composable
internal fun WhoBand.label(): String = stringResource(
    when (this) {
        WhoBand.Normal -> R.string.ds_who_normal
        WhoBand.Mild -> R.string.ds_who_mild
        WhoBand.Moderate -> R.string.ds_who_moderate
        WhoBand.ModeratelySevere -> R.string.ds_who_moderately_severe
        WhoBand.Severe -> R.string.ds_who_severe
        WhoBand.Profound -> R.string.ds_who_profound
        WhoBand.Complete -> R.string.ds_who_complete
    },
)

@Composable
internal fun WhoBand.rangeLabel(): String = when {
    this == WhoBand.Normal -> stringResource(R.string.ds_who_range_below, 20)
    toDb == null -> stringResource(R.string.ds_who_range_above, fromDb)
    else -> stringResource(R.string.ds_who_range_between, fromDb, toDb)
}

/** Tonal band colour, increasing in severity (subtle: mixes surface container with error). */
@Composable
internal fun WhoBand.tint(): Color {
    val scheme = MaterialTheme.colorScheme
    val k = ordinal / (WhoBand.entries.size - 1f)
    return lerp(scheme.surfaceContainerLow, scheme.errorContainer, 0.15f + 0.65f * k)
}

internal val AudiogramTicksHz = listOf(1000, 2000, 3000, 4000, 6000, 8000, 10000, 12500, 14000)

/** Legend for the WHO 2021 bands (approximate). Place under the audiogram. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhoBandLegend(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.ds_who_legend_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WhoBand.entries.forEach { band ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(14.dp)
                            .background(band.tint(), androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
                    )
                    Text(
                        stringResource(R.string.ds_who_band_entry, band.label(), band.rangeLabel()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Audiogram (F13): x = 1-16 kHz log, y = -10..100 dB increasing downward, WHO 2021 bands as tonal backgrounds.
 * Right ear = red ○, left ear = blue ✕ (plus legend text); censored points are drawn at their bound with a ↓;
 * low-confidence points use dashed outlines. [contentDescription] overrides the generated value listing.
 */
@Composable
fun Audiogram(
    rightEar: List<AudiogramPoint>,
    leftEar: List<AudiogramPoint>,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val ears = earColors()
    val locale = LocalConfiguration.current.locales[0]
    val measurer = rememberTextMeasurer()
    val bandTints = WhoBand.entries.map { it.tint() }
    val bandNames = WhoBand.entries.map { it.label() }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val bandStyle = labelStyle.copy(color = scheme.onSurface.copy(alpha = 0.55f), fontSize = 9.sp)
    val axisFreq = stringResource(R.string.ds_axis_frequency_khz)
    val axisLevel = stringResource(R.string.ds_axis_level_db)
    val gridColor = scheme.outlineVariant.copy(alpha = 0.6f)
    val texts = AudiogramTexts(
        earValues = stringResource(R.string.ds_audiogram_ear_values),
        point = stringResource(R.string.ds_audiogram_point),
        censored = stringResource(R.string.ds_audiogram_point_censored),
        lowConfidence = stringResource(R.string.ds_audiogram_point_low_confidence),
        noData = stringResource(R.string.ds_audiogram_no_data),
    )
    val header = stringResource(R.string.ds_audiogram_description)
    val rightName = stringResource(R.string.ds_ear_right)
    val leftName = stringResource(R.string.ds_ear_left)
    val description = contentDescription ?: (
        "$header " + audiogramEarText(rightName, rightEar, locale, texts) + " " +
            audiogramEarText(leftName, leftEar, locale, texts)
        )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .semantics { this.contentDescription = description },
        ) {
            val plot = AudiogramPlot(
                left = 34.dp.toPx(), top = 8.dp.toPx(),
                right = size.width - 8.dp.toPx(), bottom = size.height - 38.dp.toPx(),
            )
            // WHO bands
            WhoBand.entries.forEachIndexed { i, band ->
                val yTop = plot.y(band.fromDb.toFloat().coerceAtLeast(ChartMath.AUDIOGRAM_MIN_DB))
                val yBottom = plot.y((band.toDb ?: 100).toFloat())
                drawRect(bandTints[i], Offset(plot.left, yTop), Size(plot.right - plot.left, yBottom - yTop))
                drawText(measurer, bandNames[i], Offset(plot.left + 4.dp.toPx(), yTop + 1.dp.toPx()), bandStyle)
            }
            // Horizontal grid: every 10 dB, labelled every 20.
            var db = -10
            while (db <= 100) {
                val y = plot.y(db.toFloat())
                drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), 0.5.dp.toPx())
                if (db % 20 == 0) drawLabel(measurer, db.toString(), Offset(plot.left - 4.dp.toPx(), y), labelStyle, alignRight = true)
                db += 10
            }
            // Vertical ticks.
            (AudiogramTicksHz + 16000).forEach { f ->
                val x = plot.x(f.toFloat())
                drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), 0.5.dp.toPx())
            }
            AudiogramTicksHz.forEach { f ->
                drawLabel(measurer, formatNumber(f / 1000.0, locale), Offset(plot.x(f.toFloat()), plot.bottom + 3.dp.toPx()), labelStyle, centerX = true)
            }
            drawLabel(measurer, axisFreq, Offset((plot.left + plot.right) / 2f, plot.bottom + 18.dp.toPx()), labelStyle, centerX = true)
            drawLabel(measurer, axisLevel, Offset(2.dp.toPx(), plot.top - 2.dp.toPx()), labelStyle, alignTopLeft = true)

            drawEar(plot, rightEar, ears.right, isRight = true)
            drawEar(plot, leftEar, ears.left, isRight = false)
        }
        if (showLegend) {
            AudiogramMarkerLegend(ears)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudiogramMarkerLegend(ears: EarColors) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.ds_legend_right_ear), color = ears.right, style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.ds_legend_left_ear), color = ears.left, style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.ds_legend_censored), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.ds_legend_low_confidence), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

internal class AudiogramPlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun x(frequencyHz: Float): Float = left + ChartMath.audiogramXFraction(frequencyHz).coerceIn(0f, 1f) * (right - left)
    fun y(db: Float): Float = top + ChartMath.audiogramYFraction(db.coerceIn(ChartMath.AUDIOGRAM_MIN_DB, ChartMath.AUDIOGRAM_MAX_DB)) * (bottom - top)
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    anchor: Offset,
    style: TextStyle,
    alignRight: Boolean = false,
    centerX: Boolean = false,
    alignTopLeft: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val topLeft = when {
        alignRight -> Offset(anchor.x - w, anchor.y - h / 2f)
        centerX -> Offset(anchor.x - w / 2f, anchor.y)
        alignTopLeft -> Offset(anchor.x, anchor.y - h)
        else -> anchor
    }
    drawText(layout, topLeft = topLeft)
}

private fun DrawScope.drawEar(plot: AudiogramPlot, points: List<AudiogramPoint>, color: Color, isRight: Boolean) {
    val sorted = points.sortedBy { it.frequencyHz }
    val centers = sorted.map { Offset(plot.x(it.frequencyHz.toFloat()), plot.y(it.hlDb)) }
    val lineW = 1.5.dp.toPx()
    for (i in 0 until sorted.size - 1) {
        val dashed = sorted[i].lowConfidence || sorted[i + 1].lowConfidence
        drawLine(
            color.copy(alpha = 0.7f), centers[i], centers[i + 1], lineW,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
        )
    }
    val r = 6.dp.toPx()
    val w = 2.dp.toPx()
    sorted.forEachIndexed { i, p ->
        val c = centers[i]
        if (isRight) drawRingMarker(c, r, color, w, p.lowConfidence) else drawCrossMarker(c, r * 0.85f, color, w, p.lowConfidence)
        if (p.censored) drawDownArrow(Offset(c.x, c.y + r + 1.dp.toPx()), 9.dp.toPx(), color, w)
    }
}

internal class AudiogramTexts(
    val earValues: String,
    val point: String,
    val censored: String,
    val lowConfidence: String,
    val noData: String,
)

internal fun audiogramEarText(earName: String, points: List<AudiogramPoint>, locale: Locale, t: AudiogramTexts): String {
    val values = if (points.isEmpty()) {
        t.noData
    } else {
        points.sortedBy { it.frequencyHz }.joinToString("; ") { p ->
            val note = (if (p.censored) t.censored else "") + (if (p.lowConfidence) t.lowConfidence else "")
            String.format(
                locale, t.point,
                formatNumber(p.frequencyHz / 1000.0, locale), formatNumber(p.hlDb.toDouble(), locale, 0), note,
            )
        }
    }
    return String.format(locale, t.earValues, earName, values)
}
