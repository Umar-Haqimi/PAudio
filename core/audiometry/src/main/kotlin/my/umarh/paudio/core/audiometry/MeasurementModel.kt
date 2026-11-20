package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Approximate WHO 2021 grade of the better ear's PTA3 (SPEC §2.1.2; labelled "approximate" in the UI). */
enum class WhoGrade { NORMAL, MILD, MODERATE, MODERATELY_SEVERE, SEVERE, PROFOUND, COMPLETE }

/** A raw threshold (dBFS) as measured; for a censored point [levelDbfs] is the bound L_max. */
data class ThresholdPoint(val freqHz: Int, val levelDbfs: Double, val censored: Boolean)

data class MeasurementResult(
    val freqsHz: List<Int>,
    /** N(f, ear) = T − R_df(f), same order as [freqsHz]. */
    val normalized: Map<Ear, List<Double>>,
    /** Anchor A = min over ears of mean(N(1k), N(2k)). */
    val anchor: Double,
    /** HL_est(f, ear) = N − A (not clamped; use [MeasurementModel.displayHl] for plotting). */
    val hlEst: Map<Ear, List<Double>>,
    val censored: Map<Ear, List<Boolean>>,
    val pta3: Map<Ear, Double>,
    val betterEarPta3: Double,
    val grade: WhoGrade,
    val ehf: Map<Ear, Double>,
    val asymmetry: Boolean,
)

/** SPEC §2.1.3 steps 1–8. */
object MeasurementModel {
    const val DISPLAY_MIN_HL = -10.0
    const val DISPLAY_MAX_HL = 100.0
    const val ASYMMETRY_DB = 15.0
    const val THOROUGH_MAX_PAIR_DIFF_DB = 15.0

    /** Display clamp only (−10…100); never apply to values used in math. */
    fun displayHl(hl: Double): Double = max(DISPLAY_MIN_HL, min(DISPLAY_MAX_HL, hl))

    /** WHO band: Normal <20 · Mild 20–<35 · Moderate 35–<50 · Mod. severe 50–<65 · Severe 65–<80 · Profound 80–<95 · Complete ≥95. */
    fun whoGrade(pta: Double): WhoGrade = when {
        pta < 20.0 -> WhoGrade.NORMAL
        pta < 35.0 -> WhoGrade.MILD
        pta < 50.0 -> WhoGrade.MODERATE
        pta < 65.0 -> WhoGrade.MODERATELY_SEVERE
        pta < 80.0 -> WhoGrade.SEVERE
        pta < 95.0 -> WhoGrade.PROFOUND
        else -> WhoGrade.COMPLETE
    }

    /** Needs all nine grid frequencies for both ears. Censored points use their bound (already in levelDbfs). */
    fun compute(thresholds: Map<Ear, List<ThresholdPoint>>): MeasurementResult {
        val grid = FrequencyGrid.TEST_FREQUENCIES_HZ
        val byEar = Ear.entries.associateWith { ear ->
            val pts = requireNotNull(thresholds[ear]) { "missing ear $ear" }.associateBy { it.freqHz }
            grid.map { f -> requireNotNull(pts[f]) { "missing threshold for $ear @ $f Hz" } }
        }
        val n = byEar.mapValues { (_, pts) -> pts.map { it.levelDbfs - Iso389_7.rdf(it.freqHz) } }
        fun idx(f: Int) = grid.indexOf(f)
        val anchor = Ear.entries.minOf { ear -> (n.getValue(ear)[idx(1000)] + n.getValue(ear)[idx(2000)]) / 2.0 }
        val hl = n.mapValues { (_, v) -> v.map { it - anchor } }
        fun mean(ear: Ear, fs: List<Int>) = fs.map { hl.getValue(ear)[idx(it)] }.average()
        val pta3 = Ear.entries.associateWith { mean(it, listOf(1000, 2000, 4000)) }
        val ehf = Ear.entries.associateWith { mean(it, listOf(10000, 12500, 14000)) }
        val better = pta3.values.min()
        val big = grid.indices.map { i ->
            abs(hl.getValue(Ear.LEFT)[i] - hl.getValue(Ear.RIGHT)[i]) >= ASYMMETRY_DB
        }
        val asym = (0 until big.size - 1).any { big[it] && big[it + 1] }
        return MeasurementResult(
            freqsHz = grid,
            normalized = n,
            anchor = anchor,
            hlEst = hl,
            censored = byEar.mapValues { (_, pts) -> pts.map { it.censored } },
            pta3 = pta3,
            betterEarPta3 = better,
            grade = whoGrade(better),
            ehf = ehf,
            asymmetry = asym,
        )
    }

    /** Convenience: build the input from engine results (every grid point must be present). */
    fun fromPointResults(results: Map<PointKey, PointResult>): Map<Ear, List<ThresholdPoint>> =
        Ear.entries.associateWith { ear ->
            results.values.filter { it.ear == ear }.sortedBy { it.freqHz }
                .map { ThresholdPoint(it.freqHz, it.levelDbfs, it.censored) }
        }

    /** Quick reliability: catch-trial false-positive rate > 20 % ⇒ unreliable. */
    fun isQuickUnreliable(falsePositiveRate: Double): Boolean = falsePositiveRate > QuickTestEngine.FALSE_POSITIVE_LIMIT

    /** Thorough reliability: a point is low-confidence when the (repeated) pair still differs by more than 15 dB. */
    fun isThoroughPointLowConfidence(descThreshold: Double, ascThreshold: Double): Boolean =
        abs(descThreshold - ascThreshold) > THOROUGH_MAX_PAIR_DIFF_DB

    /** Fraction of points flagged low-confidence (for summary copy). */
    fun lowConfidenceFraction(results: Collection<PointResult>): Double =
        if (results.isEmpty()) 0.0 else results.count { it.lowConfidence }.toDouble() / results.size
}
