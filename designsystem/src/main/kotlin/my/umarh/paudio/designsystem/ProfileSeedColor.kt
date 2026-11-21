package my.umarh.paudio.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ln

/**
 * Profile seed colour (F25), pure and deterministic.
 *
 *  - Hue: the frequency with the largest HL_est across both ears, log-mapped 1 kHz -> 200 deg ... 14 kHz -> 340 deg
 *    (clamped outside; wrapped mod 360). Ties pick the lowest frequency.
 *  - Saturation (HSV, stands in for chroma): mean |L - R| over frequencies measured on both ears, mapped linearly
 *    0 dB -> 0.35 ... 30 dB or more -> 0.85.
 *  - Value: fixed 0.75 (mid tone). MaterialKolor only consumes the seed's hue/chroma/tone, so the theme tone is
 *    unaffected by value.
 *  - No data at all: [FALLBACK] (brand blue).
 */
object ProfileSeedColor {
    val FALLBACK = Color(0xFF3F6FB5)
    const val HUE_AT_1K = 200f
    const val HUE_AT_14K = 340f
    const val SAT_MIN = 0.35f
    const val SAT_MAX = 0.85f
    const val ASYMMETRY_FULL_DB = 30f
    const val VALUE = 0.75f

    /**
     * The single seed-colour input: RAW (unclamped) HL_est per ear. Every writer (Results after a test, the app-level
     * sync) goes through this so they can never disagree.
     */
    fun fromRawHl(hlLeft: List<Double>, hlRight: List<Double>, frequenciesHz: List<Int>): Color =
        from(hlLeft.map { it.toFloat() }, hlRight.map { it.toFloat() }, frequenciesHz)

    fun from(hlLeft: List<Float?>, hlRight: List<Float?>, frequenciesHz: List<Int>): Color {
        var bestF: Int? = null
        var bestHl = Float.NEGATIVE_INFINITY
        var asymSum = 0f
        var asymN = 0
        for (i in frequenciesHz.indices) {
            val l = hlLeft.getOrNull(i)
            val r = hlRight.getOrNull(i)
            for (v in listOfNotNull(l, r)) {
                if (v > bestHl || (v == bestHl && bestF != null && frequenciesHz[i] < bestF)) {
                    bestHl = v
                    bestF = frequenciesHz[i]
                }
            }
            if (l != null && r != null) {
                asymSum += abs(l - r)
                asymN++
            }
        }
        val f = bestF ?: return FALLBACK
        return Color.hsv(hueFor(f), saturationFor(if (asymN == 0) 0f else asymSum / asymN), VALUE)
    }

    internal fun hueFor(frequencyHz: Int): Float {
        val t = (ln(frequencyHz / 1000.0) / ln(14.0)).coerceIn(0.0, 1.0).toFloat()
        val hue = HUE_AT_1K + (HUE_AT_14K - HUE_AT_1K) * t
        return ((hue % 360f) + 360f) % 360f
    }

    internal fun saturationFor(meanAbsAsymmetryDb: Float): Float {
        val t = (meanAbsAsymmetryDb / ASYMMETRY_FULL_DB).coerceIn(0f, 1f)
        return SAT_MIN + (SAT_MAX - SAT_MIN) * t
    }
}
