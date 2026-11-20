package my.umarh.paudio.core.audiometry

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * SPEC §7.2 simulated-listener suite (1000 seeded runs per profile): accuracy, trial counts, catch scheduling,
 * reliability, ear alternation, timing (§9). True thresholds are jittered per run and point (U(−2.5, +2.5) dB)
 * so errors are not quantized by the 5 dB lattice. Final bounds are the orchestrator-approved relaxation of the
 * §7.2/§9 Quick targets (docs/DECISIONS.md §18).
 */
class SimulatedListenerSuiteTest {
    private val lRef = -60.0
    private val freqs = FrequencyGrid.TEST_FREQUENCIES_HZ
    private val runs = 1000

    /** [rel] = true threshold relative to L_exp; [beyondRange] frequencies get T = L_max + 8 (censored). */
    private class Profile(val name: String, val rel: List<Double>, val rightOffset: Double, val beyondRange: Set<Int> = emptySet())

    private val profiles = listOf(
        Profile("normal", List(9) { 0.0 }, 3.0),
        Profile("sloping_hf_loss", listOf(0.0, 0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0), 4.0),
        Profile("notch_4k", listOf(0.0, 0.0, 10.0, 30.0, 20.0, 5.0, 5.0, 10.0, 10.0), -3.0),
        Profile("censored_ehf", listOf(0.0, 0.0, 0.0, 5.0, 10.0, 15.0, 20.0, 40.0, 40.0), 0.0, setOf(12500, 14000)),
    )

    private fun trueThresholds(p: Profile, rng: Random): Map<PointKey, Double> {
        val m = HashMap<PointKey, Double>()
        for ((i, f) in freqs.withIndex()) {
            val b = Iso389_7.bounds(f, lRef)
            for (ear in Ear.entries) {
                val jitter = rng.nextDouble() * 5.0 - 2.5
                val t = if (f in p.beyondRange) {
                    b.lMax + 8.0 + jitter
                } else {
                    val off = if (ear == Ear.RIGHT) p.rightOffset else 0.0
                    (b.lExp + p.rel[i] + off + jitter).coerceIn(b.lMin + 5.0, b.lMax - 8.0)
                }
                m[PointKey(ear, f)] = t
            }
        }
        return m
    }

    private fun percentile(sorted: List<Double>, q: Double): Double =
        sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]

    /** §9 timing: heard = 1.0 s; not heard = stimulus end 1.075 s + 0.5 s latency; silence U(0.4, 1.2) s. */
    private fun quickSeconds(log: List<Trial>, rng: Random): Double {
        var ms = 0.0
        for (t in log) {
            ms += if (t.response == true) 1000.0 else StimulusSynth.STIMULUS_MS + 500.0
            ms += 400.0 + 800.0 * rng.nextDouble()
        }
        return ms / 1000.0
    }

    /** Drives the engine, asserting on every trial that the ear alternates while both ears have active points. */
    private fun runChecked(engine: QuickTestEngine, responder: QuickResponder) {
        var prevEar: Ear? = null
        while (true) {
            val activeByEar = Ear.entries.associateWith { ear -> freqs.count { f -> PointKey(ear, f) !in engine.results() } }
            val t = engine.next() ?: break
            if (prevEar != null && activeByEar.getValue(prevEar.other) > 0) assertThat(t.ear).isEqualTo(prevEar.other)
            prevEar = t.ear
            engine.submit(responder.respond(t))
        }
    }

    @Test
    fun quickAccuracyTrialCountsCatchSchedulingAndTiming() {
        for ((pi, p) in profiles.withIndex()) {
            val errors = ArrayList<Double>()
            val perPoint = ArrayList<Double>()
            val totals = ArrayList<Double>()
            val seconds = ArrayList<Double>()
            var catches = 0
            var trials = 0
            var fallbacks = 0
            var points = 0
            var beyondTotal = 0
            var beyondCensored = 0
            var falseCensored = 0
            for (run in 0 until runs) {
                val seed = pi * 100_000L + run
                val truth = trueThresholds(p, Random(seed * 3 + 2))
                val listener = SimulatedListener(truth, Random(seed * 3 + 1))
                val engine = QuickTestEngine(lRef, Random(seed * 3))
                runChecked(engine, listener)
                assertThat(engine.isFinished).isTrue()
                assertThat(engine.results()).hasSize(18)
                for ((k, r) in engine.results()) {
                    points++
                    perPoint += r.trials.toDouble()
                    if (r.lowConfidence) fallbacks++
                    if (k.freqHz in p.beyondRange) {
                        beyondTotal++
                        if (r.censored) beyondCensored++
                    } else {
                        if (r.censored) falseCensored++
                        errors += abs(r.levelDbfs - truth.getValue(k))
                    }
                }
                val log = engine.trialLog
                totals += log.size.toDouble()
                seconds += quickSeconds(log, Random(seed * 3 + 5))
                val idx = log.indices.filter { log[it].isCatch }
                catches += idx.size
                trials += log.size
                idx.zipWithNext { a, b -> b - a }.forEach { assertThat(it).isAtLeast(6) } // no back-to-back catches
                for (i in 0..log.size - 10) {
                    if (idx.isNotEmpty() && i + 9 < idx.last()) assertThat(idx.any { it in i..i + 9 }).isTrue()
                }
            }
            errors.sort(); perPoint.sort(); totals.sort(); seconds.sort()
            val med = percentile(errors, 0.5)
            val p90 = percentile(errors, 0.9)
            val rate = catches.toDouble() / trials
            println(
                "QUICK ${p.name}: |err| median=%.2f p90=%.2f presentations/point median=%.1f total median=%.0f p95=%.0f ".format(
                    med, p90, percentile(perPoint, 0.5), percentile(totals, 0.5), percentile(totals, 0.95),
                ) + "catchRate=%.3f fallback15=%.3f time median=%.0fs p95=%.0fs beyondRangeCensored=%d/%d falseCensored=%d".format(
                    rate, fallbacks.toDouble() / points, percentile(seconds, 0.5), percentile(seconds, 0.95),
                    beyondCensored, beyondTotal, falseCensored,
                ),
            )
            assertThat(med).isAtMost(3.5)
            assertThat(p90).isAtMost(7.0)
            assertThat(percentile(perPoint, 0.5)).isAtMost(10.0)
            assertThat(percentile(totals, 0.5)).isAtMost(220.0)
            assertThat(percentile(totals, 0.95)).isAtMost(245.0)
            assertThat(rate).isAtLeast(0.115)
            assertThat(rate).isAtMost(0.135)
            assertThat(percentile(seconds, 0.95)).isAtMost(9 * 60.0)
            // Only a lapse (λ = 2 %) at L_max can censor a point whose threshold is inside the range.
            assertThat(falseCensored.toDouble() / points).isAtMost(0.02)
            if (beyondTotal > 0) assertThat(beyondCensored.toDouble() / beyondTotal).isAtLeast(0.9)
        }
    }

    @Test
    fun thoroughAccuracyAndTiming() {
        for ((pi, p) in profiles.withIndex()) {
            val errors = ArrayList<Double>()
            val seconds = ArrayList<Double>()
            for (run in 0 until runs) {
                val seed = 7_000_000L + pi * 100_000L + run
                val truth = trueThresholds(p, Random(seed * 3 + 2))
                val listener = SimulatedListener(truth, Random(seed * 3 + 1))
                val engine = ThoroughTestEngine(lRef, Random(seed * 3))
                val n = engine.runToCompletion(listener)
                assertThat(engine.isFinished).isTrue()
                assertThat(engine.results()).hasSize(18)
                for ((k, r) in engine.results()) {
                    if (k.freqHz in p.beyondRange) assertThat(r.levelDbfs).isEqualTo(Iso389_7.lMax(k.freqHz, lRef)) else {
                        errors += abs(r.levelDbfs - truth.getValue(k))
                    }
                }
                seconds += n * 12.0
            }
            errors.sort(); seconds.sort()
            println(
                "THOROUGH ${p.name}: |err| median=%.2f p90=%.2f time median=%.0fs p95=%.0fs".format(
                    percentile(errors, 0.5), percentile(errors, 0.9), percentile(seconds, 0.5), percentile(seconds, 0.95),
                ),
            )
            assertThat(percentile(errors, 0.5)).isAtMost(3.0)
            assertThat(percentile(seconds, 0.95)).isAtMost(10 * 60.0)
        }
    }

    @Test
    fun noisyThoroughListenerTriggersRepeatsAndKeepsEarsAlternating() {
        var repeatRuns = 0
        var lowConfidencePoints = 0
        for (run in 0 until 200) {
            val seed = 9_000_000L + run
            val truth = trueThresholds(profiles[0], Random(seed))
            val listener = SimulatedListener(truth, Random(seed + 1), thoroughNoiseSd = 9.0)
            val engine = ThoroughTestEngine(lRef, Random(seed + 2))
            var prevEar: Ear? = null
            while (true) {
                val otherLeft = prevEar?.let { engine.remainingRunCount(it.other) } ?: 0
                val r = engine.nextRun() ?: break
                if (prevEar != null && otherLeft > 0) assertThat(r.ear).isEqualTo(prevEar.other)
                prevEar = r.ear
                when (val a = listener.answerRun(r)) {
                    is SimulatedListener.RunAnswer.Confirm -> engine.confirm(a.position)
                    is SimulatedListener.RunAnswer.Limit -> engine.reportLimit(a.position)
                }
            }
            assertThat(engine.isFinished).isTrue()
            repeatRuns += engine.runResults.count { it.isRepeat }
            lowConfidencePoints += engine.results().values.count { it.lowConfidence }
        }
        assertThat(repeatRuns).isGreaterThan(100)
        assertThat(lowConfidencePoints).isGreaterThan(0)
    }

    @Test
    fun alwaysYesListenerIsFlaggedUnreliableInEveryRun() {
        for (run in 0 until runs) {
            val engine = QuickTestEngine(lRef, Random(500_000L + run))
            engine.runToCompletion(AlwaysYesListener)
            assertThat(engine.isFinished).isTrue()
            assertThat(engine.catchTrialCount).isAtLeast(1)
            assertThat(engine.falsePositiveRate).isEqualTo(1.0)
            assertThat(engine.isUnreliable).isTrue()
            assertThat(MeasurementModel.isQuickUnreliable(engine.falsePositiveRate)).isTrue()
        }
    }

    @Test
    fun honestListenerIsRarelyFlaggedUnreliable() {
        var flagged = 0
        for (run in 0 until runs) {
            val truth = trueThresholds(profiles[0], Random(900_000L + run))
            val engine = QuickTestEngine(lRef, Random(900_000L + run))
            engine.runToCompletion(SimulatedListener(truth, Random(900_000L + run + 1)))
            if (engine.isUnreliable) flagged++
        }
        assertThat(flagged).isAtMost(runs / 100)
    }
}
