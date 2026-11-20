package my.umarh.paudio.core.dsp

import kotlin.math.max

/**
 * Hearing-compensation gain `g_h(f)` for one ear (SPEC §2.1.3, "Hearing compensation curve").
 *
 * Knots at the nine test frequencies: `g_h(f_k) = clamp(s·HL_est(f_k), −6, +12)` dB.
 *  - 1–14 kHz: PCHIP ([Pchip]) in log2(f);
 *  - below 1 kHz: `g(1000)·w(f)`, `w` linear in log2(f) from 1 at 1000 Hz to 0 at 250 Hz (0 below);
 *  - 14–16 kHz: hold `g(14000)`;
 *  - 16–20 kHz: `g(14000)` fading linearly in log2(f) to 0 at 20 kHz (0 above).
 *
 * @param hlEst nine estimated hearing levels on [TEST_FREQUENCIES_HZ] (censored points already
 *   replaced by their bound).
 * @param strength compensation strength `s` (0..1).
 */
class HearingCompensation(hlEst: DoubleArray, strength: Double) {
    /** The clamped knot gains at [TEST_FREQUENCIES_HZ]. */
    val knotGainsDb: DoubleArray

    private val pchip: Pchip

    init {
        require(hlEst.size == TEST_FREQUENCIES_HZ.size) { "need ${TEST_FREQUENCIES_HZ.size} HL values" }
        require(hlEst.all { it.isFinite() }) { "HL values must be finite" }
        require(strength.isFinite() && strength in 0.0..1.0) { "strength must be in 0..1" }
        knotGainsDb = DoubleArray(hlEst.size) { (strength * hlEst[it]).coerceIn(KNOT_MIN_DB, KNOT_MAX_DB) }
        pchip = Pchip(DoubleArray(hlEst.size) { log2(TEST_FREQUENCIES_HZ[it]) }, knotGainsDb)
    }

    /** `g_h` at an arbitrary frequency. */
    fun gainDb(fHz: Double): Double {
        val l = log2(fHz)
        return when {
            fHz < 1000.0 -> knotGainsDb[0] * ((l - LOG_250) / (LOG_1000 - LOG_250)).coerceIn(0.0, 1.0)
            fHz <= 14000.0 -> pchip.eval(l)
            fHz <= 16000.0 -> knotGainsDb.last()
            else -> knotGainsDb.last() * (1.0 - (l - LOG_16000) / (LOG_20000 - LOG_16000)).coerceIn(0.0, 1.0)
        }
    }

    /** `g_h` on the 1/24-octave [DspGrid]. */
    fun onGrid(): DoubleArray = DoubleArray(DspGrid.POINTS) { gainDb(DspGrid.gridHz[it]) }

    companion object {
        val TEST_FREQUENCIES_HZ: DoubleArray =
            doubleArrayOf(1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0, 10000.0, 12500.0, 14000.0)
        const val KNOT_MIN_DB = -6.0
        const val KNOT_MAX_DB = 12.0
        private val LOG_250 = log2(250.0)
        private val LOG_1000 = log2(1000.0)
        private val LOG_16000 = log2(16000.0)
        private val LOG_20000 = log2(20000.0)
    }
}

/** Result of [CurveBuilder.build]; all curves are on the 1/24-octave [DspGrid]. */
class CurveResult(
    val ghLeft: DoubleArray,
    val ghRight: DoubleArray,
    val gLeft: DoubleArray,
    val gRight: DoubleArray,
    /** Preamp P in dB (applied through inputGain on both channels). */
    val preampDb: Double,
)

/**
 * Builds the final per-ear curve and preamp (SPEC §2.1.3 "Total curve" and "Gain staging"):
 * `G(f, ear) = clamp(g_h + g_p, −12, +15)`, `P = max(−15, −max(0, max over f, ears of G) − 0.5)`.
 */
object CurveBuilder {
    const val G_MIN_DB = -12.0
    const val G_MAX_DB = 15.0
    const val PREAMP_FLOOR_DB = -15.0
    const val PREAMP_MARGIN_DB = 0.5

    fun build(
        hlLeft: DoubleArray,
        hlRight: DoubleArray,
        strength: Double,
        gB: Double,
        gP: Double,
        gT: Double,
    ): CurveResult {
        require(gB.isFinite() && gP.isFinite() && gT.isFinite()) { "preference gains must be finite" }
        val gp = PreferenceCurve(gB, gP, gT).onGrid()
        val ghL = HearingCompensation(hlLeft, strength).onGrid()
        val ghR = HearingCompensation(hlRight, strength).onGrid()
        val gL = DoubleArray(DspGrid.POINTS) { (ghL[it] + gp[it]).coerceIn(G_MIN_DB, G_MAX_DB) }
        val gR = DoubleArray(DspGrid.POINTS) { (ghR[it] + gp[it]).coerceIn(G_MIN_DB, G_MAX_DB) }
        return CurveResult(ghL, ghR, gL, gR, preampDb(gL, gR))
    }

    /** Preamp for a pair of final curves. */
    fun preampDb(gLeft: DoubleArray, gRight: DoubleArray): Double {
        var peak = 0.0
        for (v in gLeft) peak = max(peak, v)
        for (v in gRight) peak = max(peak, v)
        return max(PREAMP_FLOOR_DB, -peak - PREAMP_MARGIN_DB)
    }
}
