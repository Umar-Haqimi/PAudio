package my.umarh.paudio.designsystem.chart

import kotlin.math.ln

/** Pure coordinate / mapping math shared by the Canvas charts (JVM-testable). */
internal object ChartMath {
    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** Wave length in dp of the test progress indicator: 48 dp at 1 kHz down to 10 dp at 14 kHz (log scale). */
    fun waveLengthDp(frequencyHz: Float): Float {
        val t = (log2(frequencyHz.toDouble() / 1000.0) / log2(14.0)).coerceIn(0.0, 1.0).toFloat()
        return 48f + (10f - 48f) * t
    }

    /** Butterfly wing half-width as fraction (0..1) of the available half-width: map(clamp(60 - HL, 0, 70)). */
    fun butterflyHalfWidthFraction(hlEst: Float): Float = (60f - hlEst).coerceIn(0f, 70f) / 70f

    /** Butterfly vertical position: 0 = 1 kHz (bottom), 1 = 14 kHz (top), log scale. */
    fun butterflyHeightFraction(frequencyHz: Float): Float =
        (ln(frequencyHz.toDouble() / 1000.0) / ln(14.0)).coerceIn(0.0, 1.0).toFloat()

    const val AUDIOGRAM_MIN_DB = -10f
    const val AUDIOGRAM_MAX_DB = 100f

    /** Audiogram x fraction (0..1) on a 1..16 kHz log axis. */
    fun audiogramXFraction(frequencyHz: Float): Float =
        (ln(frequencyHz.toDouble() / 1000.0) / ln(16.0)).toFloat()

    /** Audiogram y fraction (0 = -10 dB at top, 1 = 100 dB at bottom): increases downward. */
    fun audiogramYFraction(db: Float): Float = (db - AUDIOGRAM_MIN_DB) / (AUDIOGRAM_MAX_DB - AUDIOGRAM_MIN_DB)

    /** EQ x fraction (0..1) on a 20 Hz..20 kHz log axis. */
    fun eqXFraction(hz: Double): Float = (ln(hz / 20.0) / ln(1000.0)).toFloat()

    /** EQ y fraction (0 = +15 dB top, 1 = -15 dB bottom). */
    fun eqYFraction(db: Double): Float = ((15.0 - db) / 30.0).toFloat()

    /** Catmull-Rom segment control points (uniform, tension 0.5) from p1 to p2 with neighbours p0, p3. */
    fun catmullRomControls(p0: Pair<Float, Float>, p1: Pair<Float, Float>, p2: Pair<Float, Float>, p3: Pair<Float, Float>):
        Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val c1 = Pair(p1.first + (p2.first - p0.first) / 6f, p1.second + (p2.second - p0.second) / 6f)
        val c2 = Pair(p2.first - (p3.first - p1.first) / 6f, p2.second - (p3.second - p1.second) / 6f)
        return c1 to c2
    }
}
