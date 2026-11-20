package my.umarh.paudio.core.audiometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Calibration maths and state machine (SPEC §4 flow 3). Pure: no clocks, no audio; the caller feeds elapsed
 * time and tap events.
 *
 * Volume model: lowering the system volume by X dB raises the digital level needed to be just audible by X dB,
 * so after changing from index `a` to `b`: `L_ref' ≈ L_ref + (dB(a) − dB(b))`. This is only a first-order
 * estimate (docs/DECISIONS.md §4); the rerun re-measures.
 */
object Calibration {
    const val START_DBFS = -90.0
    const val STEP_DB = 2.0
    const val STEP_MS = 450
    const val CAP_DBFS = -10.0
    const val REACTION_ALLOWANCE_DB = 4.0
    const val TARGET_MIN_DBFS = -68.0
    const val TARGET_MAX_DBFS = -50.0
    const val TARGET_AIM_DBFS = -60.0
    const val ACCEPT_MIN_DBFS = -75.0
    const val ACCEPT_MAX_DBFS = -35.0
    const val MAX_RERUNS = 2

    /** Number of ramp steps from START to CAP inclusive (−90, −88, …, −10 → 41 levels). */
    const val RAMP_STEPS = ((CAP_DBFS - START_DBFS) / STEP_DB).toInt() + 1

    /** Ramp level `elapsedMs` after the ramp started, or null once the −10 dBFS step has run its 450 ms. */
    fun rampLevelAtMs(elapsedMs: Long): Double? {
        require(elapsedMs >= 0) { "elapsedMs must be >= 0" }
        val step = (elapsedMs / STEP_MS).toInt()
        return if (step >= RAMP_STEPS) null else START_DBFS + STEP_DB * step
    }

    fun lRefFromTap(tapLevelDbfs: Double): Double = tapLevelDbfs - REACTION_ALLOWANCE_DB

    /** Starting STREAM_MUSIC index: round(0.5 × max). */
    fun initialIndex(maxIndex: Int): Int = (0.5 * maxIndex).roundToInt()

    fun inTargetWindow(lRef: Double): Boolean = lRef in TARGET_MIN_DBFS..TARGET_MAX_DBFS

    fun inAcceptWindow(lRef: Double): Boolean = lRef in ACCEPT_MIN_DBFS..ACCEPT_MAX_DBFS

    /**
     * Pick the index in [[minIndex], [maxIndex]] whose predicted `L_ref` is closest to [TARGET_AIM_DBFS].
     * Returns null when the current index is already the best choice (or nothing better is known).
     * [indexDb] mirrors `VolumeController.indexDb`: null for unknown/−∞ values (those indices are skipped).
     * With no dB information for the current index, falls back to a ±[fallbackStep] nudge in the right direction.
     */
    fun planIndexChange(
        currentIndex: Int,
        minIndex: Int,
        maxIndex: Int,
        lRef: Double,
        indexDb: (Int) -> Float?,
    ): Int? {
        val curDb = indexDb(currentIndex)?.takeIf { it.isFinite() }?.toDouble()
        if (curDb == null) return fallbackStep(currentIndex, minIndex, maxIndex, lRef)
        var best = currentIndex
        var bestErr = abs(lRef - TARGET_AIM_DBFS)
        for (i in minIndex..maxIndex) {
            if (i == currentIndex) continue
            val d = indexDb(i)?.takeIf { it.isFinite() }?.toDouble() ?: continue
            val predicted = lRef + (curDb - d)
            val err = abs(predicted - TARGET_AIM_DBFS)
            if (err < bestErr - 1e-9) {
                best = i
                bestErr = err
            }
        }
        return if (best == currentIndex) null else best
    }

    /** Predicted `L_ref` after moving from [currentIndex] to [newIndex]; null if dB values are unknown. */
    fun predictLRef(lRef: Double, currentIndex: Int, newIndex: Int, indexDb: (Int) -> Float?): Double? {
        val a = indexDb(currentIndex)?.takeIf { it.isFinite() } ?: return null
        val b = indexDb(newIndex)?.takeIf { it.isFinite() } ?: return null
        return lRef + (a - b)
    }

    /** No-dB path: L_ref above the aim ⇒ the volume is too low (tone must be loud) ⇒ raise the index; else lower it. */
    fun fallbackStep(currentIndex: Int, minIndex: Int, maxIndex: Int, lRef: Double): Int? {
        val step = max(1, (0.1 * maxIndex).roundToInt())
        val target = if (lRef > TARGET_AIM_DBFS) currentIndex + step else currentIndex - step
        val clamped = target.coerceIn(minIndex, maxIndex)
        return if (clamped == currentIndex) null else clamped
    }
}

enum class CalibrationAbortReason { CANT_HEAR, VOLUME_RANGE_UNSUITABLE }

data class CalibrationResult(
    val index: Int,
    val maxIndex: Int,
    val lRefDbfs: Double,
    /** True when accepted outside the target window but inside [−75, −35] (UI shows a warning). */
    val warning: Boolean,
)

sealed interface CalibrationOutcome {
    /** Run the ramp again at [newIndex] (caller sets the volume and reads it back, then calls [CalibrationSession.restart]). */
    data class Rerun(val newIndex: Int, val lRefDbfs: Double) : CalibrationOutcome
    data class Accepted(val result: CalibrationResult) : CalibrationOutcome
    data class Aborted(val reason: CalibrationAbortReason, val lRefDbfs: Double? = null) : CalibrationOutcome
}

/**
 * Small state machine: one session covers the initial run and up to [Calibration.MAX_RERUNS] reruns.
 * Usage: set the volume to [currentIndex], play the ramp ([Calibration.rampLevelAtMs]); on a tap call [onTap]
 * with the level playing at that moment (or [onRampExhausted] if the ramp ran out); on `Rerun`, apply the index
 * (pass the effective, read-back index to [restart]) and repeat.
 */
class CalibrationSession(
    val minIndex: Int,
    val maxIndex: Int,
    private val indexDb: (Int) -> Float?,
) {
    var currentIndex: Int = Calibration.initialIndex(maxIndex).coerceIn(minIndex, maxIndex)
        private set
    var reruns: Int = 0
        private set
    var outcome: CalibrationOutcome? = null
        private set

    /** Level to play [elapsedMs] after the current ramp began; null once exhausted. */
    fun rampLevelAtMs(elapsedMs: Long): Double? = Calibration.rampLevelAtMs(elapsedMs)

    fun onRampExhausted(): CalibrationOutcome =
        CalibrationOutcome.Aborted(CalibrationAbortReason.CANT_HEAR).also { outcome = it }

    fun onTap(tapLevelDbfs: Double): CalibrationOutcome {
        val lRef = Calibration.lRefFromTap(tapLevelDbfs)
        val result = CalibrationResult(currentIndex, maxIndex, lRef, warning = !Calibration.inTargetWindow(lRef))
        val o: CalibrationOutcome = when {
            Calibration.inTargetWindow(lRef) -> CalibrationOutcome.Accepted(result)
            reruns < Calibration.MAX_RERUNS -> {
                val next = Calibration.planIndexChange(currentIndex, minIndex, maxIndex, lRef, indexDb)
                if (next == null) finalVerdict(result) else CalibrationOutcome.Rerun(next, lRef)
            }
            else -> finalVerdict(result)
        }
        outcome = o
        return o
    }

    /** Call after applying a [CalibrationOutcome.Rerun]; [effectiveIndex] is the read-back volume index. */
    fun restart(effectiveIndex: Int) {
        check(outcome is CalibrationOutcome.Rerun) { "restart() only after a Rerun outcome" }
        currentIndex = effectiveIndex.coerceIn(minIndex, maxIndex)
        reruns++
        outcome = null
    }

    private fun finalVerdict(result: CalibrationResult): CalibrationOutcome =
        if (Calibration.inAcceptWindow(result.lRefDbfs)) {
            CalibrationOutcome.Accepted(result.copy(warning = true))
        } else {
            CalibrationOutcome.Aborted(CalibrationAbortReason.VOLUME_RANGE_UNSUITABLE, result.lRefDbfs)
        }
}
