package my.umarh.paudio.feature.results

import my.umarh.paudio.audio.playback.AbCurve
import my.umarh.paudio.audio.playback.AbGainPlanner
import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.dsp.CurveResult
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.model.EqConfig

/**
 * Loudness-matched Original/Personalized pair for one (test, profile settings, clip-or-pink, mixer rate).
 * Each option is swapped in with `swapTo(cfg, matchGainDb)`: `cfg.preampDb = P_common` (shared) and
 * `matchGainDb = -DeltaL(G)` (0 for Original), so the effect input gain is `P_common - DeltaL(G)`.
 */
class AbPlan(
    val commonPreampDb: Float,
    val deltaLDb: Double,
    val originalCfg: EqConfig,
    val originalMatchGainDb: Float,
    val personalizedCfg: EqConfig,
    val personalizedMatchGainDb: Float,
)

/** Profile settings that shape the Personalized curve (SPEC 2.1.3). */
data class CurveSettings(val strength: Double, val gB: Double, val gP: Double, val gT: Double)

object AbPlanner {
    /**
     * Final per-ear curve G(f) = clamp(g_h + g_p, -12, +15) on the 1/24-octave DspGrid for THIS test's HL, the profile's
     * compensation strength and its preference gains. Independent of clip and sample rate (used for the EQ graph too).
     */
    fun curve(content: ResultsContent, s: CurveSettings): CurveResult =
        CurveBuilder.build(content.hlLeft, content.hlRight, s.strength, s.gB, s.gP, s.gT)

    /**
     * Both options share one preamp and are loudness-matched by `AbGainPlanner` using [predictor] (clip LTAS, or pink for
     * user files). The DP configs come from `AbGainPlanner.configFor` -> `DpEqPlanner.fromCurve` / `flat` for the real
     * mixer rate [fs]; cutoffs are never built by hand.
     */
    fun plan(curve: CurveResult, fs: Int, predictor: LoudnessPredictor, ladder: DpLadderStep = DpLadderStep.MAIN): AbPlan {
        val plan = AbGainPlanner.resultsAb(curve.gLeft, curve.gRight, predictor)
        return AbPlan(
            commonPreampDb = plan.commonPreampDb,
            deltaLDb = plan.options[1].deltaLDb,
            originalCfg = AbGainPlanner.configFor(AbCurve.Flat, plan, fs, ladder),
            originalMatchGainDb = plan.options[0].matchGainDb,
            personalizedCfg = AbGainPlanner.configFor(AbCurve.Curve(curve.gLeft, curve.gRight), plan, fs, ladder),
            personalizedMatchGainDb = plan.options[1].matchGainDb,
        )
    }
}
