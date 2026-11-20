package my.umarh.paudio.feature.health

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
import my.umarh.paudio.designsystem.chart.AudiogramPoint
import my.umarh.paudio.designsystem.chart.TrendPoint

/** Per-ear estimated summary. Values are estimated relative HL (SPEC 2.1.3), never calibrated dB HL. */
@Immutable
data class EarSummary(
    val ear: Ear,
    val pta3: Double,
    /** Extended high-frequency average (10 / 12.5 / 14 kHz). Shown as a number only, never graded. */
    val ehf: Double,
    /** At least one EHF point was not reached at the loudest allowed level ("beyond test range"). */
    val ehfBeyondRange: Boolean,
)

@Immutable
data class Reliability(val unreliable: Boolean, val lowConfidencePoints: Int) {
    val hasNote: Boolean get() = unreliable || lowConfidencePoints > 0
}

@Immutable
data class HealthContent(
    val profileId: Long,
    val testId: Long,
    val testStartedAt: Long,
    val mode: TestMode,
    val rightPoints: List<AudiogramPoint>,
    val leftPoints: List<AudiogramPoint>,
    val right: EarSummary,
    val left: EarSummary,
    /** Approximate WHO grade of the better ear's PTA3 (the model's grade ceiling is documented in DECISIONS 17). */
    val grade: WhoGrade,
    val betterEarPta3: Double,
    val anyCensored: Boolean,
    val asymmetry: Boolean,
    val reliability: Reliability,
    /** Oldest first; same profile AND same headset only. Shown when >= 2. */
    val trend: List<TrendPoint>,
) {
    val showTrend: Boolean get() = trend.size >= 2
}

enum class UnavailableReason { TEST_MISSING, PROFILE_MISSING, INCOMPLETE, ERROR }

sealed interface HealthUiState {
    data object Loading : HealthUiState
    data object Empty : HealthUiState
    /**
     * Something was asked for (or stored) that cannot be shown. [canShowLatest] offers a switch to the latest test;
     * it is never true for a missing profile or a load error.
     */
    data class Unavailable(val reason: UnavailableReason, val canShowLatest: Boolean = false) : HealthUiState
    data class Content(val content: HealthContent) : HealthUiState
}

/** Pure mapping from stored raw thresholds to UI pieces. Derived values are recomputed every time, never stored. */
object HealthMapper {
    /** Returns null when the stored thresholds are incomplete (every grid point of both ears is required). */
    fun measure(thresholds: List<ThresholdEntity>): MeasurementResult? {
        val byEar = Ear.entries.associateWith { ear ->
            thresholds.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
        }
        val complete = byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }
        if (!complete) return null
        val points = byEar.mapValues { (_, pts) ->
            FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
                val t = pts.getValue(f)
                ThresholdPoint(f, t.levelDbfs!!.toDouble(), t.censored)
            }
        }
        return MeasurementModel.compute(points)
    }

    fun audiogramPoints(result: MeasurementResult, thresholds: List<ThresholdEntity>, ear: Ear): List<AudiogramPoint> {
        val lowConf = thresholds.filter { it.ear == ear && it.lowConfidence }.map { it.freqHz }.toSet()
        val hl = result.hlEst.getValue(ear)
        val cens = result.censored.getValue(ear)
        return result.freqsHz.mapIndexed { i, f ->
            AudiogramPoint(
                frequencyHz = f,
                hlDb = MeasurementModel.displayHl(hl[i]).toFloat(),
                censored = cens[i],
                lowConfidence = f in lowConf,
            )
        }
    }

    fun earSummary(result: MeasurementResult, ear: Ear): EarSummary {
        val cens = result.censored.getValue(ear)
        val ehfIdx = listOf(10000, 12500, 14000).map { result.freqsHz.indexOf(it) }
        return EarSummary(
            ear = ear,
            pta3 = result.pta3.getValue(ear),
            ehf = result.ehf.getValue(ear),
            ehfBeyondRange = ehfIdx.any { cens[it] },
        )
    }

    fun reliability(test: HearingTestEntity, thresholds: List<ThresholdEntity>): Reliability =
        Reliability(unreliable = !test.reliable, lowConfidencePoints = thresholds.count { it.lowConfidence })

    /** Null when the thresholds are incomplete. */
    fun content(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trend: List<TrendPoint>): HealthContent? {
        val result = measure(thresholds) ?: return null
        return HealthContent(
            profileId = test.profileId,
            testId = test.id,
            testStartedAt = test.startedAt,
            mode = test.mode,
            rightPoints = audiogramPoints(result, thresholds, Ear.RIGHT),
            leftPoints = audiogramPoints(result, thresholds, Ear.LEFT),
            right = earSummary(result, Ear.RIGHT),
            left = earSummary(result, Ear.LEFT),
            grade = result.grade,
            betterEarPta3 = result.betterEarPta3,
            anyCensored = result.censored.values.any { c -> c.any { it } },
            asymmetry = result.asymmetry,
            reliability = reliability(test, thresholds),
            trend = trend,
        )
    }

    /** PTA3 per ear of one test, recomputed from its raw thresholds; null when incomplete. */
    fun trendPoint(test: HearingTestEntity, thresholds: List<ThresholdEntity>): TrendPoint? {
        val r = measure(thresholds) ?: return null
        return TrendPoint(
            dateMillis = test.startedAt,
            leftPta = r.pta3.getValue(Ear.LEFT).toFloat(),
            rightPta = r.pta3.getValue(Ear.RIGHT).toFloat(),
        )
    }
}
