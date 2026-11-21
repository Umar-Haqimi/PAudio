package my.umarh.paudio.feature.preference

import kotlinx.serialization.Serializable
import my.umarh.paudio.audio.playback.AbCurve
import my.umarh.paudio.audio.playback.AbGainPlanner
import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.dsp.CurveResult
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.core.preference.BisectionRound
import my.umarh.paudio.core.preference.PreferenceAxis

/**
 * Two loudness-matched options (A/B, or Original/Personalized) that share one preamp. Each is swapped in with
 * `swapTo(cfg[i], matchGainDb[i])`: `cfg.preampDb = commonPreampDb` and `matchGainDb = -DeltaL` so the effect input gain
 * is `P_common - DeltaL(G)` (AbGainPlanner, SPEC 2.1.5).
 */
class PrefPlan(
    val commonPreampDb: Float,
    val configs: List<EqConfig>,
    val matchGainDb: List<Float>,
    val deltaLDb: List<Double>,
)

/** Pure planning for the preference test; nothing here touches Android. */
object PreferencePlanner {
    /**
     * Total curve G = clamp(g_h + g_p) (SPEC 2.1.3). g_h comes from the baseline's HL at its compensation [strength];
     * with no hearing test the HL is all zero, so only the preference curve acts (flat hearing compensation).
     */
    fun curve(b: PreferenceBaseline, strength: Double, gB: Double, gP: Double, gT: Double): CurveResult =
        CurveBuilder.build(b.hlLeft, b.hlRight, strength, gB, gP, gT)

    /**
     * The curves of both sides of [round]: the compared axis takes the side's gain, the other two axes their current
     * centres [centres] (`[gB, gP, gT]`). Hearing compensation uses the baseline's own strength.
     */
    fun roundCurves(b: PreferenceBaseline, centres: DoubleArray, round: BisectionRound): Pair<CurveResult, CurveResult> {
        fun side(axisGain: Double): CurveResult {
            val g = centres.copyOf()
            g[round.axis.ordinal] = axisGain
            return curve(b, b.strength, g[0], g[1], g[2])
        }
        return side(round.aGain) to side(round.bGain)
    }

    fun plan(options: List<AbCurve>, predictor: LoudnessPredictor, fs: Int, ladder: DpLadderStep = DpLadderStep.MAIN): PrefPlan {
        val gains = AbGainPlanner.plan(options, predictor)
        return PrefPlan(
            commonPreampDb = gains.commonPreampDb,
            configs = options.map { AbGainPlanner.configFor(it, gains, fs, ladder) },
            matchGainDb = gains.options.map { it.matchGainDb },
            deltaLDb = gains.options.map { it.deltaLDb },
        )
    }

    fun roundPlan(a: CurveResult, b: CurveResult, predictor: LoudnessPredictor, fs: Int): PrefPlan =
        plan(listOf(AbCurve.Curve(a.gLeft, a.gRight), AbCurve.Curve(b.gLeft, b.gRight)), predictor, fs)

    /** Fine-tune: option 0 = Original (flat), option 1 = Personalized (final curve). */
    fun fineTunePlan(c: CurveResult, predictor: LoudnessPredictor, fs: Int): PrefPlan =
        plan(listOf(AbCurve.Flat, AbCurve.Curve(c.gLeft, c.gRight)), predictor, fs)
}

/** Clamp ranges of the fine-tune sliders (the bisection clamps, SPEC section 4 flow 5). */
object FineTuneRange {
    val bass = PreferenceAxis.BASS.minGain..PreferenceAxis.BASS.maxGain
    val presence = PreferenceAxis.PRESENCE.minGain..PreferenceAxis.PRESENCE.maxGain
    val treble = PreferenceAxis.TREBLE.minGain..PreferenceAxis.TREBLE.maxGain

    fun of(axis: PreferenceAxis): ClosedFloatingPointRange<Double> = axis.minGain..axis.maxGain
}

/** One answered round as stored in `PreferenceTest.roundsJson` (the core log plus the clip that played). */
@Serializable
data class RoundRecord(
    val round: Int,
    val axis: String,
    val aGain: Double,
    val bGain: Double,
    val plusSide: String,
    /** "A", "B" or "CANT_TELL". */
    val choice: String,
    /** The picked gain in dB; null for "Can't tell". */
    val pickedGain: Double?,
    val clip: String,
) {
    companion object {
        fun of(round: BisectionRound, choice: AbSide?, clip: String) = RoundRecord(
            round = round.index,
            axis = round.axis.name,
            aGain = round.aGain,
            bGain = round.bGain,
            plusSide = round.plusSide.name,
            choice = choice?.name ?: "CANT_TELL",
            pickedGain = when (choice) {
                AbSide.A -> round.aGain
                AbSide.B -> round.bGain
                null -> null
            },
            clip = clip,
        )
    }
}
