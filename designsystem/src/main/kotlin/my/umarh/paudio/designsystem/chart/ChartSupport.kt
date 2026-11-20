package my.umarh.paudio.designsystem.chart

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import my.umarh.paudio.designsystem.PAudioTheme
import java.text.NumberFormat
import java.util.Locale

/** True when the system animator duration scale is 0 (reduced motion): skip decorative animation. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/** Ear colours for charts: right = error-red (○), left = accessible blue (✕); never the only carrier of meaning. */
@Immutable
internal class EarColors(val right: Color, val left: Color)

@Composable
internal fun earColors(): EarColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return EarColors(
        right = scheme.error,
        left = if (dark) Color(0xFF8AB4FF) else Color(0xFF1B5FCC),
    )
}

internal fun formatNumber(value: Double, locale: Locale, maxFraction: Int = 1): String =
    NumberFormat.getInstance(locale).apply { maximumFractionDigits = maxFraction; minimumFractionDigits = 0 }.format(value)

/** Smooth open path through [points] (Catmull-Rom converted to cubics). */
internal fun Path.catmullRomThrough(points: List<Offset>, moveToFirst: Boolean = true) {
    if (points.isEmpty()) return
    if (moveToFirst) moveTo(points[0].x, points[0].y) else lineTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
}

internal fun DrawScope.dashEffect(dashed: Boolean): PathEffect? =
    if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null

/** Right-ear marker ○. */
internal fun DrawScope.drawRingMarker(center: Offset, radius: Float, color: Color, strokeWidth: Float, dashed: Boolean = false) {
    drawCircle(color, radius, center, style = Stroke(strokeWidth, pathEffect = dashEffect(dashed)))
}

/** Left-ear marker ✕. */
internal fun DrawScope.drawCrossMarker(center: Offset, radius: Float, color: Color, strokeWidth: Float, dashed: Boolean = false) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(radius * 0.6f, radius * 0.4f)) else null
    drawLine(color, Offset(center.x - radius, center.y - radius), Offset(center.x + radius, center.y + radius), strokeWidth, pathEffect = effect)
    drawLine(color, Offset(center.x - radius, center.y + radius), Offset(center.x + radius, center.y - radius), strokeWidth, pathEffect = effect)
}

/** Small downward arrow ↓ below a censored point. */
internal fun DrawScope.drawDownArrow(top: Offset, length: Float, color: Color, strokeWidth: Float) {
    val tip = Offset(top.x, top.y + length)
    drawLine(color, top, tip, strokeWidth)
    drawLine(color, tip, Offset(tip.x - length * 0.35f, tip.y - length * 0.35f), strokeWidth)
    drawLine(color, tip, Offset(tip.x + length * 0.35f, tip.y - length * 0.35f), strokeWidth)
}

/** Light + dark previews for the design system components. */
@Preview(name = "Light", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF000000)
internal annotation class PAudioPreviews

@Composable
internal fun PreviewFrame(content: @Composable () -> Unit) {
    val dark = (androidx.compose.ui.platform.LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = dark) {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}
