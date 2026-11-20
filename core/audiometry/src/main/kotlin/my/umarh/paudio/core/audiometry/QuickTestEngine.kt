package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.TrialJson
import kotlin.random.Random

/**
 * One presented Quick-test trial. [response] is null while pending and `true` = "heard".
 * Catch trials are silent: [levelDbfs] is `null` and [ear]/[freqHz] are the slot the scheduler had picked
 * (so the UI/log looks like a normal trial). [voided] marks trials discarded by [QuickTestEngine.restartPoint]
 * (interruptions): the engine log keeps them, the persistence layer must drop them
 * ([QuickTestEngine.persistableTrials]). Timing is the caller's business (no clocks here).
 */
data class Trial(
    val seq: Int,
    val ear: Ear,
    val freqHz: Int,
    val levelDbfs: Double?,
    val isCatch: Boolean,
    val response: Boolean? = null,
    val voided: Boolean = false,
)

/**
 * Quick test engine (SPEC §4 "Quick detail (normative)"): one [HughsonWestlakeStaircase] per (ear, f), a
 * scheduler that alternates ears, and catch trials. Deterministic given [rng]. No coroutines, no clocks.
 *
 * Drive: `while (true) { val t = engine.next() ?: break; present(t); engine.submit(heard) }`.
 * `next()` is idempotent until [submit] (or [cancelPending]) is called.
 *
 * Scheduling: the next trial picks a uniformly random *active* staircase in the ear opposite to the previous
 * trial's ear (same ear only if the other ear has no active staircase). The first trial picks uniformly among
 * all active staircases. Catch (silent) trials: the distance between consecutive catches (and from the start to
 * the first) is drawn uniformly from {6,…,10} trials, i.e. 5–9 non-catch trials in between, so the catch rate
 * is 1/8 on average, no two catches are adjacent and every window of 10 trials contains at least one. The
 * decision is made in `next()` from a counter (idempotent); the next gap is drawn when a catch is *submitted*.
 * A catch keeps its scheduled ear for the alternation rule but does not advance any staircase. False-positive rate = heard-on-catch / catch trials (0 when there were no catches);
 * above [FALSE_POSITIVE_LIMIT] (20 %) the test is [isUnreliable].
 */
class QuickTestEngine(
    private val lRefDbfs: Double,
    private val rng: Random,
    private val frequencies: List<Int> = FrequencyGrid.TEST_FREQUENCIES_HZ,
    private val ears: List<Ear> = listOf(Ear.LEFT, Ear.RIGHT),
) {
    private val keys: List<PointKey> = frequencies.flatMap { f -> ears.map { e -> PointKey(e, f) } }
    private val staircases = HashMap<PointKey, HughsonWestlakeStaircase>()
    private val finished = LinkedHashMap<PointKey, PointResult>()
    private val log = mutableListOf<Trial>()
    private var pending: Trial? = null
    private var lastEar: Ear? = null
    private var consecutiveNonCatch = 0
    private var nonCatchBeforeNextCatch = drawGap()
    private var seq = 0
    private var catchTrials = 0
    private var catchHeard = 0

    val totalPoints: Int get() = keys.size
    val completedPoints: Int get() = finished.size
    val isFinished: Boolean get() = finished.size == keys.size

    /** Frequency of the pending trial, if any. */
    val currentFrequencyHz: Int? get() = pending?.freqHz

    /** 0..1: finished points plus partial credit (up to 0.9, saturating at 8 trials) for points in progress. */
    val progress: Double
        get() {
            var p = finished.size.toDouble()
            for ((k, s) in staircases) if (k !in finished) p += 0.9 * minOf(1.0, s.trialCount / 8.0)
            return p / keys.size
        }

    /** Answered trials in presentation order (including voided ones, flagged). */
    val trialLog: List<Trial> get() = log

    fun results(): Map<PointKey, PointResult> = finished

    val catchTrialCount: Int get() = catchTrials
    val catchHeardCount: Int get() = catchHeard
    val falsePositiveRate: Double get() = if (catchTrials == 0) 0.0 else catchHeard.toDouble() / catchTrials
    val isUnreliable: Boolean get() = falsePositiveRate > FALSE_POSITIVE_LIMIT

    /** The next trial to present, or null when every point is finished. */
    fun next(): Trial? {
        pending?.let { return it }
        val active = keys.filter { it !in finished }
        if (active.isEmpty()) return null
        val wanted = lastEar?.other
        val sameEarPool = wanted?.let { w -> active.filter { it.ear == w } }.orEmpty()
        val pool = if (sameEarPool.isNotEmpty()) sameEarPool else active
        val key = pool[rng.nextInt(pool.size)]
        val isCatch = consecutiveNonCatch >= nonCatchBeforeNextCatch
        val trial = if (isCatch) {
            Trial(++seq, key.ear, key.freqHz, null, isCatch = true)
        } else {
            val sc = staircases.getOrPut(key) { newStaircase(key) }
            Trial(++seq, key.ear, key.freqHz, sc.currentLevel, isCatch = false)
        }
        pending = trial
        return trial
    }

    /** Answer the pending trial: `true` = "I heard it". */
    fun submit(heard: Boolean) {
        val t = checkNotNull(pending) { "no pending trial; call next() first" }
        pending = null
        log += t.copy(response = heard)
        lastEar = t.ear
        if (t.isCatch) {
            catchTrials++
            if (heard) catchHeard++
            consecutiveNonCatch = 0
            nonCatchBeforeNextCatch = drawGap()
            return
        }
        consecutiveNonCatch++
        val key = PointKey(t.ear, t.freqHz)
        val sc = staircases.getValue(key)
        sc.submit(heard)
        sc.result?.let { r ->
            finished[key] = PointResult(key.ear, key.freqHz, r.levelDbfs, r.censored, r.lowConfidence, r.trials, r.atFloor)
        }
    }

    /**
     * Trials to persist: voided trials (interrupted points) are dropped, `seq` is renumbered contiguously from 1
     * and catch trials have `levelDbfs = null`. [tMsOf] maps the engine `seq` to milliseconds since test start
     * (the engine has no clock).
     */
    fun persistableTrials(tMsOf: (Int) -> Long = { 0L }): List<TrialJson> =
        log.filter { !it.voided }.mapIndexed { i, t ->
            TrialJson(
                seq = i + 1, ear = t.ear, freqHz = t.freqHz, levelDbfs = t.levelDbfs?.toFloat(),
                isCatch = t.isCatch, response = t.response == true, run = null, tMs = tMsOf(t.seq),
            )
        }

    /** Drop the pending trial unanswered (e.g. pause); the scheduler will pick again. */
    fun cancelPending() {
        pending = null
    }

    /**
     * Restart only this staircase from scratch (pause / volume change, F8): its previous trials are marked
     * [Trial.voided], its result is removed and any pending trial for it is dropped. Other points are untouched.
     */
    fun restartPoint(ear: Ear, freqHz: Int) {
        val key = PointKey(ear, freqHz)
        require(key in keys) { "unknown point $key" }
        staircases.remove(key)
        finished.remove(key)
        for (i in log.indices) {
            val t = log[i]
            if (!t.isCatch && t.ear == ear && t.freqHz == freqHz && !t.voided) log[i] = t.copy(voided = true)
        }
        pending?.let { if (!it.isCatch && it.ear == ear && it.freqHz == freqHz) pending = null }
    }

    private fun newStaircase(key: PointKey): HughsonWestlakeStaircase {
        val b = Iso389_7.bounds(key.freqHz, lRefDbfs)
        val other = finished[PointKey(key.ear.other, key.freqHz)]?.levelDbfs
        return HughsonWestlakeStaircase(b.lExp, b.lMax, b.lMin, other)
    }

    private fun drawGap(): Int = MIN_CATCH_SPACING - 1 + rng.nextInt(MAX_CATCH_SPACING - MIN_CATCH_SPACING + 1)

    companion object {
        /** Catch-to-catch distance in trials: uniform in 6..10 (mean 8 ⇒ 12.5 %). */
        const val MIN_CATCH_SPACING = 6
        const val MAX_CATCH_SPACING = 10
        const val FALSE_POSITIVE_LIMIT = 0.20
    }
}
