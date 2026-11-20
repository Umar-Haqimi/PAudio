package my.umarh.paudio.audio.playback

import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.dsp.DP_FRAME_MS
import my.umarh.paudio.core.dsp.DpBandLayout
import my.umarh.paudio.core.dsp.DpBandMapper
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.dsp.dpBlockSize
import my.umarh.paudio.core.model.EqConfig
import kotlin.math.max

/** One option of an in-app A/B comparison: no EQ, or a final per-ear curve G on the 1/24-oct DspGrid. */
sealed interface AbCurve {
    /** Original (bypass-equivalent): G = 0 everywhere. */
    data object Flat : AbCurve

    class Curve(val gLeft: DoubleArray, val gRight: DoubleArray) : AbCurve
}

/**
 * Per-option result of [AbGainPlanner.plan].
 *
 * @property deltaLDb predicted loudness change ΔL(G) of the option (0 for flat).
 * @property matchGainDb the loudness-match term `-ΔL(G)`. Pass this as `inputGainDb` to
 *   `AbPlayer.swapTo(cfg, inputGainDb)` together with an [EqConfig] whose `preampDb` is the plan's
 *   [AbGainPlan.commonPreampDb] (see [AbGainPlanner.configFor]).
 * @property effectInputGainDb the resulting total DP inputGain = `P_common - ΔL(G)`.
 */
data class AbOptionGain(val deltaLDb: Double, val matchGainDb: Float, val effectInputGainDb: Float)

data class AbGainPlan(val commonPreampDb: Float, val options: List<AbOptionGain>)

/**
 * Loudness-matched gain staging for in-app A/B (SPEC §2.1.5, DECISIONS §1).
 *
 * ```
 * P_common          = max(-15, -max(0, max over ALL options, ears, grid points of G) - 0.5)
 * effectInputGain_i = P_common - ΔL(G_i)     (ΔL(flat) = 0; ΔL from LoudnessPredictor, clip LTAS or pink)
 * ```
 * All options share P_common, so the boost of no option can clip, and the options then differ only by
 * the predicted loudness difference, which is cancelled. The match gain is applied to in-app A/B
 * players only, never to the system-wide engine.
 */
object AbGainPlanner {
    fun plan(options: List<AbCurve>, predictor: LoudnessPredictor): AbGainPlan {
        require(options.isNotEmpty()) { "no options" }
        var peak = 0.0
        for (o in options) if (o is AbCurve.Curve) {
            for (v in o.gLeft) peak = max(peak, v)
            for (v in o.gRight) peak = max(peak, v)
        }
        val common = max(CurveBuilder.PREAMP_FLOOR_DB, -peak - CurveBuilder.PREAMP_MARGIN_DB).toFloat()
        val gains = options.map { o ->
            val dl = when (o) {
                AbCurve.Flat -> 0.0
                is AbCurve.Curve -> predictor.deltaLDb(o.gLeft, o.gRight)
            }
            AbOptionGain(dl, 0f - dl.toFloat(), (common - dl).toFloat())
        }
        return AbGainPlan(common, gains)
    }

    /** Results A/B: option 0 = Original (flat), option 1 = Personalized (final curve G). */
    fun resultsAb(gLeft: DoubleArray, gRight: DoubleArray, predictor: LoudnessPredictor): AbGainPlan =
        plan(listOf(AbCurve.Flat, AbCurve.Curve(gLeft, gRight)), predictor)

    /**
     * Preference round: options are the full curves (hearing compensation + preference curve, built via
     * `CurveBuilder.build`) of the c-s and c+s candidates.
     */
    fun preferenceRound(low: AbCurve.Curve, high: AbCurve.Curve, predictor: LoudnessPredictor): AbGainPlan =
        plan(listOf(low, high), predictor)

    /** The [EqConfig] to hand to `swapTo` for [curve]: DP band layout/gains, preamp = P_common. */
    fun configFor(curve: AbCurve, plan: AbGainPlan, fs: Int = 48_000): EqConfig {
        val layout = DpBandLayout.of(fs, dpBlockSize(fs, DP_FRAME_MS))
        val cutoffs = List(layout.bandCount) { layout.cutoffHz[it].toFloat() }
        val (l, r) = when (curve) {
            AbCurve.Flat -> FloatArray(layout.bandCount) to FloatArray(layout.bandCount)
            is AbCurve.Curve -> DpBandMapper.map(layout, curve.gLeft) to DpBandMapper.map(layout, curve.gRight)
        }
        return EqConfig(plan.commonPreampDb, cutoffs, listOf(l.toList(), r.toList()))
    }
}
