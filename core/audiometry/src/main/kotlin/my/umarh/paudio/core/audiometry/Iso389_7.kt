package my.umarh.paudio.core.audiometry

import kotlin.math.log2
import kotlin.math.min

/**
 * ISO 389-7 diffuse-field reference threshold table and the level bounds of SPEC §2.1.3.
 * All levels are dBFS = 20·log10(peak amplitude of float samples, full scale = 1.0).
 */
object Iso389_7 {
    /** R_df (dB SPL) per SPEC §2.1.2 (USAARL Table 11-1, from ISO 389-7:2005). Sorted by frequency. */
    val TABLE: List<Pair<Int, Double>> = listOf(
        1000 to 0.8, 1250 to 1.9, 1500 to 1.0, 1600 to 0.5, 2000 to -1.5, 2500 to -3.1,
        3000 to -4.0, 3150 to -4.0, 4000 to -3.8, 5000 to -1.8, 6000 to 1.4, 6300 to 2.5,
        8000 to 6.8, 9000 to 8.4, 10000 to 9.8, 11200 to 11.5, 12500 to 14.4, 14000 to 23.2,
        16000 to 43.7,
    )

    /** Absolute ceiling of any presented tone (dBFS). */
    const val MAX_DBFS = -3.0

    /** R_df(f): exact table value, else linear interpolation in log2(f); clamped outside 1–16 kHz. */
    fun rdf(freqHz: Double): Double {
        val first = TABLE.first()
        val last = TABLE.last()
        if (freqHz <= first.first) return first.second
        if (freqHz >= last.first) return last.second
        for (i in 0 until TABLE.size - 1) {
            val (fa, ra) = TABLE[i]
            val (fb, rb) = TABLE[i + 1]
            if (freqHz == fa.toDouble()) return ra
            if (freqHz > fa && freqHz <= fb) {
                if (freqHz == fb.toDouble()) return rb
                val t = (log2(freqHz) - log2(fa.toDouble())) / (log2(fb.toDouble()) - log2(fa.toDouble()))
                return ra + t * (rb - ra)
            }
        }
        error("unreachable")
    }

    fun rdf(freqHz: Int): Double = rdf(freqHz.toDouble())

    /** Expected level at f for a calibrated L_ref: `L_ref + (R_df(f) − R_df(1000))`. */
    fun lExp(freqHz: Int, lRefDbfs: Double): Double = lRefDbfs + (rdf(freqHz) - rdf(1000))

    /** Never present above this; a threshold not reached here is censored. */
    fun lMax(freqHz: Int, lRefDbfs: Double): Double = min(MAX_DBFS, lExp(freqHz, lRefDbfs) + 50.0)

    /** Lower presentation bound. */
    fun lMin(freqHz: Int, lRefDbfs: Double): Double = lExp(freqHz, lRefDbfs) - 25.0

    /** All three bounds for one frequency. */
    fun bounds(freqHz: Int, lRefDbfs: Double): LevelBounds =
        LevelBounds(lExp(freqHz, lRefDbfs), lMin(freqHz, lRefDbfs), lMax(freqHz, lRefDbfs))
}

data class LevelBounds(val lExp: Double, val lMin: Double, val lMax: Double)
