package my.umarh.paudio.feature.results

import androidx.compose.runtime.Immutable
import my.umarh.paudio.core.audiometry.MeasurementModel
import my.umarh.paudio.core.audiometry.MeasurementResult
import my.umarh.paudio.core.audiometry.ThresholdPoint
import my.umarh.paudio.core.audiometry.WhoGrade
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ThresholdEntity

/**
 * Plain-words band of one ear's estimated PTA3. It is derived from the WHO band edges (SPEC 2.1.2) but is deliberately
 * NOT the WHO grade: PTA3 here is relative to the better ear at 1-2 kHz, so the words describe the estimate only and
 * never say anything about healthy or unhealthy hearing (DECISIONS 17).
 */
enum class PtaWording { SIMILAR, SLIGHTLY_HIGHER, HIGHER, MUCH_HIGHER }

/** Per-ear estimated summary (SPEC 2.1.3), never calibrated dB HL. */
@Immutable
data class EarResult(
    val ear: Ear,
    val pta3: Double,
    val wording: PtaWording,
    /** At least one 10 / 12.5 / 14 kHz point was not reached at the loudest allowed level ("beyond test range"). */
    val ehfBeyondRange: Boolean,
    /** Any grid point of this ear was censored (not reached at L_max). */
    val anyCensored: Boolean,
)

@Immutable
data class ReliabilityNote(
    /** Quick: catch-trial false-positive rate above 20 % (stored as `reliable = false`). */
    val unreliable: Boolean,
    val mode: TestMode,
    /** Thorough: points whose repeated pair still differed by more than 15 dB. */
    val lowConfidencePoints: Int,
) {
    val offerRedo: Boolean get() = unreliable
    val hasNote: Boolean get() = unreliable || lowConfidencePoints > 0
}

/**
 * Everything the Results screen needs from one stored test. [hlLeft]/[hlRight] are the raw (unclamped) HL_est used for the
 * compensation curve; the `*Display` lists are the display-clamped (-10..100) values for the butterfly.
 */
@Immutable
class ResultsContent(
    val profileId: Long,
    val testId: Long,
    val mode: TestMode,
    val frequenciesHz: List<Int>,
    val hlLeft: DoubleArray,
    val hlRight: DoubleArray,
    val leftDisplay: List<Float>,
    val rightDisplay: List<Float>,
    val censoredLeft: List<Boolean>,
    val censoredRight: List<Boolean>,
    val left: EarResult,
    val right: EarResult,
    val asymmetry: Boolean,
    val reliability: ReliabilityNote,
) {
    val anyCensored: Boolean get() = left.anyCensored || right.anyCensored
}

/** Pure mapping from stored raw thresholds to Results UI pieces. Derived values are recomputed, never stored. */
object ResultsMapper {
    /** Null when the stored thresholds are incomplete (every grid point of both ears is required). */
    fun measure(thresholds: List<ThresholdEntity>): MeasurementResult? {
        val byEar = Ear.entries.associateWith { ear ->
            thresholds.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
        }
        if (!byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }) return null
        val points = byEar.mapValues { (_, pts) ->
            FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
                val t = pts.getValue(f)
                ThresholdPoint(f, t.levelDbfs!!.toDouble(), t.censored)
            }
        }
        return MeasurementModel.compute(points)
    }

    fun wording(pta3: Double): PtaWording = when (MeasurementModel.whoGrade(pta3)) {
        WhoGrade.NORMAL -> PtaWording.SIMILAR
        WhoGrade.MILD -> PtaWording.SLIGHTLY_HIGHER
        WhoGrade.MODERATE, WhoGrade.MODERATELY_SEVERE -> PtaWording.HIGHER
        WhoGrade.SEVERE, WhoGrade.PROFOUND, WhoGrade.COMPLETE -> PtaWording.MUCH_HIGHER
    }

    /** HL_est per grid frequency, display-clamped for plotting (censored points stay at their bound). */
    fun butterflyInput(result: MeasurementResult, ear: Ear): List<Float> =
        result.hlEst.getValue(ear).map { MeasurementModel.displayHl(it).toFloat() }

    fun earResult(result: MeasurementResult, ear: Ear): EarResult {
        val cens = result.censored.getValue(ear)
        val ehfIdx = listOf(10000, 12500, 14000).map { result.freqsHz.indexOf(it) }
        val pta = result.pta3.getValue(ear)
        return EarResult(
            ear = ear,
            pta3 = pta,
            wording = wording(pta),
            ehfBeyondRange = ehfIdx.any { cens[it] },
            anyCensored = cens.any { it },
        )
    }

    fun reliability(test: HearingTestEntity, thresholds: List<ThresholdEntity>): ReliabilityNote =
        ReliabilityNote(
            unreliable = !test.reliable,
            mode = test.mode,
            lowConfidencePoints = thresholds.count { it.lowConfidence },
        )

    /** Null when the thresholds are incomplete. */
    fun content(test: HearingTestEntity, thresholds: List<ThresholdEntity>): ResultsContent? {
        val result = measure(thresholds) ?: return null
        return ResultsContent(
            profileId = test.profileId,
            testId = test.id,
            mode = test.mode,
            frequenciesHz = result.freqsHz,
            hlLeft = result.hlEst.getValue(Ear.LEFT).toDoubleArray(),
            hlRight = result.hlEst.getValue(Ear.RIGHT).toDoubleArray(),
            leftDisplay = butterflyInput(result, Ear.LEFT),
            rightDisplay = butterflyInput(result, Ear.RIGHT),
            censoredLeft = result.censored.getValue(Ear.LEFT),
            censoredRight = result.censored.getValue(Ear.RIGHT),
            left = earResult(result, Ear.LEFT),
            right = earResult(result, Ear.RIGHT),
            asymmetry = result.asymmetry,
            reliability = reliability(test, thresholds),
        )
    }
}
