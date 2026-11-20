package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** One (ear, frequency) measurement point. */
data class PointKey(val ear: Ear, val freqHz: Int)

/** Final result of one measurement point (Quick or Thorough). */
data class PointResult(
    val ear: Ear,
    val freqHz: Int,
    /** Threshold in dBFS; for a censored point the bound L_max ("≥ L_max"). */
    val levelDbfs: Double,
    val censored: Boolean,
    val lowConfidence: Boolean,
    /** Presentations (Quick) or runs (Thorough) used. */
    val trials: Int,
    /** True when the listener still heard the tone at the L_min floor (threshold ≤ L_min). */
    val atFloor: Boolean = false,
)

/** Outcome of a single staircase. */
data class StaircaseResult(
    val levelDbfs: Double,
    val censored: Boolean,
    val lowConfidence: Boolean,
    val trials: Int,
    val atFloor: Boolean,
)

/**
 * Modified Hughson-Westlake staircase for one (ear, f) point — SPEC §4 "Quick detail (normative)".
 * Pure state machine: [currentLevel] is what to present next; call [submit] with the listener's answer.
 *
 * Rules (the golden set 8 derivations in docs/VECTORS.md are the precise reference):
 *  - Start = `min(L_max, otherEarThreshold + 15)` if known else `min(L_max, L_exp + 20)`, then `max(L_min, ·)`.
 *  - SEEK phase (until the first "heard"): not heard → +10 (clamped to L_max); not heard *at* L_max → censored.
 *    The first "heard" switches to the HW phase.
 *  - HW phase: heard → −10 (clamped to L_min), not heard → +5 (clamped to L_max).
 *  - A presentation is *ascending* iff the previous answer of this staircase was "not heard" (the level was
 *    reached by an upward step); the very first presentation is not ascending. Per level, only the first
 *    3 ascending presentations count. Threshold = the first level reaching 2 "heard" among its ascending
 *    presentations (so it is the lowest such level, stopping immediately).
 *  - Floor: if the listener hears the tone at L_min twice (any presentation at L_min, including repeated clamped
 *    ones) the threshold is L_min with [StaircaseResult.atFloor] = true (not flagged low-confidence).
 *  - Ceiling in HW phase: two "not heard" at L_max → censored at L_max.
 *  - Max 15 presentations. If still undecided: fallback = mean of the last 4 reversal levels, low-confidence.
 *  - **Reversal**: when an answer differs from the previous answer of this staircase, the level of the
 *    presentation that received the *new* answer is a reversal level (the turning point of the track: the
 *    valley after heard→not heard, the peak after not heard→heard). The first answer never makes a reversal.
 *    Fewer than 4 reversals → mean of all of them; none → mean of the last 4 presented levels.
 */
class HughsonWestlakeStaircase(
    val lExp: Double,
    val lMax: Double,
    val lMin: Double,
    otherEarThresholdDbfs: Double? = null,
) {
    val startLevel: Double =
        max(lMin, min(lMax, if (otherEarThresholdDbfs != null) otherEarThresholdDbfs + 15.0 else lExp + 20.0))

    var currentLevel: Double = startLevel
        private set
    var result: StaircaseResult? = null
        private set
    val isFinished: Boolean get() = result != null

    private val levelList = mutableListOf<Double>()
    private val answerList = mutableListOf<Boolean>()
    private val reversalList = mutableListOf<Double>()
    private var seeking = true
    private var nextIsAscending = false
    private val ascPresented = HashMap<Long, Int>()
    private val ascHeard = HashMap<Long, Int>()
    private var floorHeard = 0
    private var ceilingMisses = 0

    val levels: List<Double> get() = levelList
    val answers: List<Boolean> get() = answerList
    val reversals: List<Double> get() = reversalList
    val trialCount: Int get() = levelList.size

    fun submit(heard: Boolean) {
        check(result == null) { "staircase already finished" }
        val level = currentLevel
        val ascending = nextIsAscending
        levelList += level
        if (answerList.isNotEmpty() && answerList.last() != heard) reversalList += level
        answerList += heard

        val key = (level * 1000.0).roundToLong()
        if (ascending) {
            val p = ascPresented.getOrDefault(key, 0)
            if (p < MAX_ASC_PER_LEVEL) {
                ascPresented[key] = p + 1
                if (heard) {
                    val h = ascHeard.getOrDefault(key, 0) + 1
                    ascHeard[key] = h
                    if (h >= 2) return finish(level, censored = false, low = false)
                }
            }
        }
        val atMin = level <= lMin + EPS
        val atMax = level >= lMax - EPS
        if (heard && atMin) {
            floorHeard++
            if (floorHeard >= 2) return finish(lMin, censored = false, low = false)
        }
        if (!heard && atMax) {
            ceilingMisses++
            if (seeking || ceilingMisses >= 2) return finish(lMax, censored = true, low = false)
        }
        if (trialCount >= MAX_TRIALS) {
            val src = if (reversalList.isNotEmpty()) reversalList else levelList
            val tail = src.takeLast(4)
            return finish(tail.average(), censored = false, low = true)
        }

        if (heard) {
            seeking = false
            currentLevel = max(lMin, level - 10.0)
            nextIsAscending = false
        } else {
            currentLevel = min(lMax, level + if (seeking) 10.0 else 5.0)
            nextIsAscending = true
        }
    }

    private fun finish(level: Double, censored: Boolean, low: Boolean) {
        result = StaircaseResult(level, censored, low, trialCount, atFloor = !censored && level <= lMin + EPS)
    }

    companion object {
        const val MAX_TRIALS = 15
        const val MAX_ASC_PER_LEVEL = 3
        private const val EPS = 1e-9
    }
}
