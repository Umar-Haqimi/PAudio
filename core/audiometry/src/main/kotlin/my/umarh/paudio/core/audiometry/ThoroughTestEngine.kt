package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class RunKind { DESCENDING, ASCENDING }

/**
 * One Thorough-test run: a looping pulsed tone and a level dial (SPEC §4 "Thorough detail").
 * The UI shows **no numbers**; it only maps a dial position (integer detent, 1 dB per detent) to a level through
 * [levelAt] and plays that level. The dial's physical position range is [minPosition]..[maxPosition]; the
 * position that corresponds to [L_min] is randomised per run ([positionOffset]), so position carries no
 * information about the level. [initialPosition] is where the dial starts (level [startLevelDbfs]).
 */
class DialRun internal constructor(
    val ear: Ear,
    val freqHz: Int,
    val kind: RunKind,
    val lMin: Double,
    val lMax: Double,
    val positionOffset: Int,
    val initialPosition: Int,
    /** True if this run repeats a pair whose first runs disagreed by > 15 dB. */
    val isRepeat: Boolean,
) {
    val minPosition: Int = 0
    val maxPosition: Int = positionOffset + kotlin.math.ceil(lMax - lMin).toInt() + EXTRA_DETENTS_ABOVE

    /** Dial position → level (dBFS), 1 dB per detent, clamped to [L_min, L_max]. */
    fun levelAt(position: Int): Double = max(lMin, min(lMax, lMin + (position - positionOffset)))

    val startLevelDbfs: Double get() = levelAt(initialPosition)

    companion object {
        const val EXTRA_DETENTS_ABOVE = 10
        const val MAX_OFFSET = 40
    }
}

/** One recorded Thorough run threshold. */
data class RunResult(
    val ear: Ear,
    val freqHz: Int,
    val kind: RunKind,
    val confirmedLevelDbfs: Double,
    /** descending → confirmed + 1 dB, ascending → confirmed (clamped to [L_min, L_max]). */
    val thresholdDbfs: Double,
    val isRepeat: Boolean,
    /** Dial reached L_max and the listener still could not hear (censored point). */
    val censored: Boolean = false,
    /** Dial reached L_min and the listener still heard it. */
    val atFloor: Boolean = false,
)

/**
 * Thorough test engine. Pure and deterministic given [rng]; no clocks or coroutines.
 *
 * Order: frequencies in a random permutation; for each frequency four runs alternate between the two ears:
 * `A-descending, B-descending, A-ascending, B-ascending` where A is a random ear fixed for the whole test, so
 * the ear alternates between every pair of consecutive runs (including across frequencies). Descending runs
 * start at `L_exp + U(15, 25)`, ascending runs at `L_exp − U(15, 25)` (clamped into [L_min, L_max]).
 *
 * Per point: descending threshold = confirmed + 1 dB, ascending threshold = confirmed; point = mean. If
 * |desc − asc| > 15 dB the pair is repeated once (the two repeat runs are queued immediately next); the final
 * point = mean of all four run thresholds, and it is low-confidence if the repeated pair still differs by
 * more than 15 dB. A censored/floor report ends the point immediately (remaining runs for it are dropped).
 *
 * Usage: `val run = engine.nextRun() ?: done; ... engine.confirm(position)` or `engine.reportLimit(position)`.
 */
class ThoroughTestEngine(
    private val lRefDbfs: Double,
    private val rng: Random,
    frequencies: List<Int> = FrequencyGrid.TEST_FREQUENCIES_HZ,
    private val ears: List<Ear> = listOf(Ear.LEFT, Ear.RIGHT),
) {
    private data class Planned(val key: PointKey, val kind: RunKind, val isRepeat: Boolean)

    private val queue = ArrayDeque<Planned>()
    private val keys: List<PointKey>
    private val runsByPoint = HashMap<PointKey, MutableList<RunResult>>()
    private val finished = LinkedHashMap<PointKey, PointResult>()
    private val repeatedPoints = HashSet<PointKey>()
    private val runLog = mutableListOf<RunResult>()
    private var current: DialRun? = null

    init {
        val order = frequencies.shuffled(rng)
        val firstEar = ears[rng.nextInt(ears.size)]
        val seqEars = listOf(firstEar) + ears.filter { it != firstEar }
        keys = order.flatMap { f -> ears.map { PointKey(it, f) } }
        for (f in order) {
            for (kind in RunKind.entries) {
                for (e in seqEars) queue.addLast(Planned(PointKey(e, f), kind, false))
            }
        }
    }

    val totalPoints: Int get() = keys.size
    val completedPoints: Int get() = finished.size
    val isFinished: Boolean get() = finished.size == keys.size
    val progress: Double get() = finished.size.toDouble() / keys.size
    val currentFrequencyHz: Int? get() = current?.freqHz
    fun results(): Map<PointKey, PointResult> = finished
    val runResults: List<RunResult> get() = runLog

    /** The run to present next (idempotent until answered), or null when the test is complete. */
    fun nextRun(): DialRun? {
        current?.let { return it }
        val p = queue.firstOrNull() ?: return null
        val b = Iso389_7.bounds(p.key.freqHz, lRefDbfs)
        val raw = when (p.kind) {
            RunKind.DESCENDING -> b.lExp + 15.0 + 10.0 * rng.nextDouble()
            RunKind.ASCENDING -> b.lExp - 15.0 - 10.0 * rng.nextDouble()
        }
        val start = max(b.lMin, min(b.lMax, raw))
        val offset = rng.nextInt(DialRun.MAX_OFFSET + 1)
        val initialPos = offset + (start - b.lMin).roundToInt()
        val run = DialRun(p.key.ear, p.key.freqHz, p.kind, b.lMin, b.lMax, offset, initialPos, p.isRepeat)
        current = run
        return run
    }

    /** The listener confirmed at [position]: "I can't hear it anymore" (descending) / "I can hear it now" (ascending). */
    fun confirm(position: Int) {
        val run = checkNotNull(current) { "no current run" }
        val confirmed = run.levelAt(position)
        val thr = when (run.kind) {
            RunKind.DESCENDING -> min(run.lMax, confirmed + 1.0)
            RunKind.ASCENDING -> confirmed
        }
        record(run, RunResult(run.ear, run.freqHz, run.kind, confirmed, thr, run.isRepeat))
    }

    /**
     * The dial is at a hard limit and the listener cannot confirm: at L_max "I can't hear it even at the top" →
     * the point is censored (≥ L_max); at L_min "I still hear it at the bottom" → threshold ≤ L_min (floor).
     * Throws if the dial at [position] is not at either limit.
     */
    fun reportLimit(position: Int) {
        val run = checkNotNull(current) { "no current run" }
        val level = run.levelAt(position)
        val atMax = level >= run.lMax - 1e-9
        val atMin = level <= run.lMin + 1e-9
        require(atMax || atMin) { "dial is not at a limit" }
        val rr = if (atMax) {
            RunResult(run.ear, run.freqHz, run.kind, run.lMax, run.lMax, run.isRepeat, censored = true)
        } else {
            RunResult(run.ear, run.freqHz, run.kind, run.lMin, run.lMin, run.isRepeat, atFloor = true)
        }
        record(run, rr)
    }

    /** Abandon the current run (pause); it will be presented again with fresh random parameters. */
    fun cancelRun() {
        current = null
    }

    private fun record(run: DialRun, rr: RunResult) {
        current = null
        val key = PointKey(run.ear, run.freqHz)
        queue.removeFirst()
        runLog += rr
        val runs = runsByPoint.getOrPut(key) { mutableListOf() }
        runs += rr

        if (rr.censored || rr.atFloor) {
            // Hard-limit outcome ends the point; drop its remaining runs.
            queue.removeAll { it.key == key }
            finished[key] = PointResult(
                key.ear, key.freqHz, rr.thresholdDbfs, censored = rr.censored, lowConfidence = false,
                trials = runs.size, atFloor = rr.atFloor,
            )
            return
        }
        val last2 = runs.takeLast(2)
        if (last2.size == 2 && last2.map { it.kind }.toSet().size == 2 && queue.none { it.key == key }) {
            // The latest pair (desc + asc) is complete.
            val diff = kotlin.math.abs(last2[0].thresholdDbfs - last2[1].thresholdDbfs)
            if (diff > MAX_PAIR_DIFF_DB && key !in repeatedPoints) {
                repeatedPoints += key
                queue.addFirst(Planned(key, RunKind.ASCENDING, true))
                queue.addFirst(Planned(key, RunKind.DESCENDING, true))
            } else {
                finished[key] = PointResult(
                    key.ear, key.freqHz, runs.map { it.thresholdDbfs }.average(),
                    censored = false, lowConfidence = diff > MAX_PAIR_DIFF_DB, trials = runs.size,
                )
            }
        }
    }

    companion object {
        const val MAX_PAIR_DIFF_DB = 15.0
    }
}
