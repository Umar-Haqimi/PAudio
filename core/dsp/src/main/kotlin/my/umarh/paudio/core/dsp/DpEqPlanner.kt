package my.umarh.paudio.core.dsp

/** Frame duration the effect requests from DynamicsProcessing (DECISIONS §1, N = 1024 at 44.1/48 kHz). */
const val DP_FRAME_MS: Float = 16f

/** Which layout of the fallback ladder to use (DECISIONS §1 "Fallback ladder"). */
enum class DpLadderStep { MAIN, FB51, SPEC40 }

/**
 * Everything `:audio:effect` needs to program DynamicsProcessing for one curve: shared [layout],
 * Float cutoffs (`stop·fs/N` computed in double), per-ear band gains, the preamp for inputGain and
 * the underlying [curve] (null for a flat plan).
 */
class DpEqPlan(
    val layout: DpBandLayout,
    val cutoffHz: FloatArray,
    val gainsLeft: FloatArray,
    val gainsRight: FloatArray,
    val preampDb: Float,
    val curve: CurveResult?,
) {
    /** Predicted loudness change of the plan's curve; 0 dB for a flat plan (SPEC §2.1.5). */
    fun loudnessDeltaDb(predictor: LoudnessPredictor): Double =
        curve?.let { predictor.deltaLDb(it.gLeft, it.gRight) } ?: 0.0
}

/** Single entry point turning HL estimates + preferences into a DP plan (SPEC §2.1.3–2.1.4, DECISIONS §1). */
object DpEqPlanner {
    fun plan(
        hlLeft: DoubleArray,
        hlRight: DoubleArray,
        strength: Double,
        gB: Double,
        gP: Double,
        gT: Double,
        fs: Int,
        frameMs: Float = DP_FRAME_MS,
        ladder: DpLadderStep = DpLadderStep.MAIN,
    ): DpEqPlan {
        val curve = CurveBuilder.build(hlLeft, hlRight, strength, gB, gP, gT)
        val layout = layoutFor(fs, frameMs, ladder)
        return DpEqPlan(
            layout = layout,
            cutoffHz = FloatArray(layout.bandCount) { layout.cutoffHz[it].toFloat() },
            gainsLeft = DpBandMapper.map(layout, curve.gLeft),
            gainsRight = DpBandMapper.map(layout, curve.gRight),
            preampDb = curve.preampDb.toFloat(),
            curve = curve,
        )
    }

    /** All-zero gains and 0 dB preamp (Original mode). */
    fun flat(fs: Int, frameMs: Float = DP_FRAME_MS, ladder: DpLadderStep = DpLadderStep.MAIN): DpEqPlan {
        val layout = layoutFor(fs, frameMs, ladder)
        return DpEqPlan(
            layout, FloatArray(layout.bandCount) { layout.cutoffHz[it].toFloat() },
            FloatArray(layout.bandCount), FloatArray(layout.bandCount), 0f, null,
        )
    }

    private fun layoutFor(fs: Int, frameMs: Float, ladder: DpLadderStep): DpBandLayout {
        require(fs > 0) { "fs must be positive" }
        val n = dpBlockSize(fs, frameMs)
        return when (ladder) {
            DpLadderStep.MAIN -> DpBandLayout.of(fs, n)
            DpLadderStep.FB51 -> DpBandLayout.fallback51(fs, n)
            DpLadderStep.SPEC40 -> DpBandLayout.spec40(fs, n)
        }
    }
}
