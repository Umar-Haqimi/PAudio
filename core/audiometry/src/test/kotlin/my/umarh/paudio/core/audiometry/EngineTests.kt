package my.umarh.paudio.core.audiometry

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import org.junit.Test
import kotlin.random.Random

class QuickEngineTest {
    private val lRef = -60.0
    private val freqs = FrequencyGrid.TEST_FREQUENCIES_HZ

    private fun truth(rel: Double = 0.0): Map<PointKey, Double> = buildMap {
        for (f in freqs) for (e in Ear.entries) {
            val b = Iso389_7.bounds(f, lRef)
            put(PointKey(e, f), (b.lExp + rel).coerceIn(b.lMin + 5, b.lMax - 8))
        }
    }

    @Test
    fun neverPresentsOutsideBoundsAndNextIsIdempotent() {
        for (seed in 0 until 50) {
            val engine = QuickTestEngine(lRef, Random(seed))
            val listener = SimulatedListener(truth(20.0), Random(seed + 1000L))
            while (true) {
                val t = engine.next() ?: break
                assertThat(engine.next()).isSameInstanceAs(t)
                if (!t.isCatch) {
                    val b = Iso389_7.bounds(t.freqHz, lRef)
                    assertThat(t.levelDbfs!!).isAtMost(b.lMax + 1e-9)
                    assertThat(t.levelDbfs!!).isAtLeast(b.lMin - 1e-9)
                } else {
                    assertThat(t.levelDbfs).isNull()
                }
                engine.submit(listener.respond(t))
            }
            assertThat(engine.isFinished).isTrue()
            for (r in engine.results().values) assertThat(r.trials).isAtMost(HughsonWestlakeStaircase.MAX_TRIALS)
            assertThat(engine.progress).isWithin(1e-9).of(1.0)
        }
    }

    @Test
    fun catchSchedulingSpacingRateAndIdempotence() {
        var totalTrials = 0
        var totalCatch = 0
        for (seed in 0 until 200) {
            val engine = QuickTestEngine(lRef, Random(seed))
            engine.runToCompletion(SimulatedListener(truth(10.0), Random(seed + 1L)))
            val idx = engine.trialLog.indices.filter { engine.trialLog[it].isCatch }
            // First catch at trial 6..10 (1-based) unless the test is shorter; spacing 6..10 afterwards.
            if (idx.isNotEmpty()) assertThat(idx.first() + 1).isIn(6..10)
            idx.zipWithNext { a, b -> b - a }.forEach { assertThat(it).isIn(6..10) }
            // every window of 10 consecutive trials has a catch (until the last catch)
            for (i in 0..engine.trialLog.size - 10) {
                if (idx.isNotEmpty() && i + 9 < idx.last()) assertThat(idx.any { it in i..i + 9 }).isTrue()
            }
            totalTrials += engine.trialLog.size
            totalCatch += idx.size
        }
        val rate = totalCatch.toDouble() / totalTrials
        assertThat(rate).isGreaterThan(0.115)
        assertThat(rate).isLessThan(0.135)
    }

    @Test
    fun persistableTrialsDropVoidedRenumberAndNullCatchLevels() {
        val engine = QuickTestEngine(lRef, Random(21), frequencies = listOf(1000, 2000))
        val listener = SimulatedListener(truth(), Random(22))
        var restarted = false
        while (true) {
            val t = engine.next() ?: break
            if (!restarted && !t.isCatch && engine.trialLog.count { !it.isCatch } >= 3) {
                restarted = true
                engine.restartPoint(t.ear, t.freqHz)
                continue
            }
            engine.submit(listener.respond(t))
        }
        val voided = engine.trialLog.count { it.voided }
        assertThat(voided).isGreaterThan(0)
        val p = engine.persistableTrials { it * 1000L }
        assertThat(p).hasSize(engine.trialLog.size - voided)
        assertThat(p.map { it.seq }).isEqualTo((1..p.size).toList())
        assertThat(p.filter { it.isCatch }.all { it.levelDbfs == null }).isTrue()
        assertThat(p.filter { !it.isCatch }.all { it.levelDbfs != null }).isTrue()
    }

    @Test
    fun falsePositiveRateAndUnreliableThreshold() {
        val engine = QuickTestEngine(lRef, Random(5))
        // Answer catch trials "heard" in 3 of 10, others "heard" (keeps staircases alive) → 30 % > 20 % unreliable; 2 of 10 → reliable.
        var catches = 0
        while (catches < 10) {
            val t = checkNotNull(engine.next())
            if (t.isCatch) {
                engine.submit(catches < 2)
                catches++
            } else {
                engine.submit(true)
            }
        }
        assertThat(engine.falsePositiveRate).isWithin(1e-12).of(0.2)
        assertThat(engine.isUnreliable).isFalse() // exactly 20 % is not "> 20 %"
        while (true) {
            val t = checkNotNull(engine.next())
            if (t.isCatch) {
                engine.submit(true)
                break
            }
            engine.submit(true)
        }
        assertThat(engine.isUnreliable).isTrue()
    }

    @Test
    fun censoredPointWhenNeverHeard() {
        val engine = QuickTestEngine(lRef, Random(9), frequencies = listOf(1000))
        while (true) {
            val t = engine.next() ?: break
            engine.submit(false)
        }
        val r = engine.results().values
        assertThat(r).hasSize(2)
        for (p in r) {
            assertThat(p.censored).isTrue()
            assertThat(p.levelDbfs).isEqualTo(Iso389_7.lMax(1000, lRef))
        }
        assertThat(engine.isUnreliable).isFalse() // no catch was heard
    }

    @Test
    fun secondEarStartsFromOtherEarThresholdPlusFifteen() {
        // Single frequency: ear A staircase must finish before B starts only if scheduled so; check the rule directly.
        val sc = HughsonWestlakeStaircase(-60.0, -10.0, -85.0, otherEarThresholdDbfs = -52.0)
        assertThat(sc.startLevel).isEqualTo(-37.0)
        assertThat(HughsonWestlakeStaircase(-60.0, -10.0, -85.0).startLevel).isEqualTo(-40.0)
        assertThat(HughsonWestlakeStaircase(-60.0, -10.0, -85.0, -20.0).startLevel).isEqualTo(-10.0) // clamp ≤ Lmax
        assertThat(HughsonWestlakeStaircase(-60.0, -10.0, -85.0, -120.0).startLevel).isEqualTo(-85.0) // clamp ≥ Lmin
    }

    @Test
    fun restartPointResetsOnlyThatStaircase() {
        val engine = QuickTestEngine(lRef, Random(11), frequencies = listOf(1000, 2000))
        val listener = SimulatedListener(truth(), Random(12))
        // Run until some point finished, then restart a different in-progress point.
        var restarted: PointKey? = null
        while (true) {
            val t = engine.next() ?: break
            if (restarted == null && !t.isCatch && engine.trialLog.count { !it.isCatch && it.ear == t.ear && it.freqHz == t.freqHz } == 2) {
                restarted = PointKey(t.ear, t.freqHz)
                val before = engine.results().toMap()
                engine.restartPoint(t.ear, t.freqHz)
                assertThat(engine.results()).isEqualTo(before)
                assertThat(engine.trialLog.filter { it.voided }.size).isEqualTo(2)
                assertThat(engine.trialLog.filter { it.voided }.all { it.ear == t.ear && it.freqHz == t.freqHz }).isTrue()
                continue
            }
            engine.submit(listener.respond(t))
        }
        assertThat(engine.isFinished).isTrue()
        val key = checkNotNull(restarted)
        // The restarted point's result only counts its fresh presentations.
        val fresh = engine.trialLog.count { !it.isCatch && !it.voided && it.ear == key.ear && it.freqHz == key.freqHz }
        assertThat(engine.results().getValue(key).trials).isEqualTo(fresh)
    }

    @Test
    fun cancelPendingLetsSchedulerPickAgain() {
        val engine = QuickTestEngine(lRef, Random(1))
        val first = checkNotNull(engine.next())
        engine.cancelPending()
        assertThat(engine.currentFrequencyHz).isNull()
        val second = checkNotNull(engine.next())
        assertThat(second.seq).isGreaterThan(first.seq)
    }
}

class ThoroughEngineTest {
    private val lRef = -60.0

    @Test
    fun dialMappingAndRunStarts() {
        val engine = ThoroughTestEngine(lRef, Random(4))
        var runs = 0
        var prevEar: Ear? = null
        while (true) {
            val otherLeft = prevEar?.let { engine.remainingRunCount(it.other) } ?: 0
            val run = engine.nextRun() ?: break
            val b = Iso389_7.bounds(run.freqHz, lRef)
            assertThat(run.levelAt(run.initialPosition)).isEqualTo(run.startLevelDbfs)
            when (run.kind) {
                RunKind.DESCENDING -> {
                    assertThat(run.startLevelDbfs).isAtLeast(minOf(b.lExp + 14.5, b.lMax))
                    assertThat(run.startLevelDbfs).isAtMost(minOf(b.lExp + 25.5, b.lMax))
                }
                RunKind.ASCENDING -> {
                    assertThat(run.startLevelDbfs).isAtLeast(b.lExp - 25.5)
                    assertThat(run.startLevelDbfs).isAtMost(b.lExp - 14.5)
                }
            }
            // 1 dB per detent, clamped to bounds; dial extremes clamp.
            assertThat(run.levelAt(run.initialPosition + 1) - run.levelAt(run.initialPosition)).isAnyOf(1.0, run.lMax - run.startLevelDbfs)
            assertThat(run.levelAt(run.minPosition)).isEqualTo(run.lMin)
            assertThat(run.levelAt(run.maxPosition)).isEqualTo(run.lMax)
            // Ear alternates between consecutive runs.
            // Ear alternates between consecutive runs (repeats included) while the other ear still has runs left.
            if (prevEar != null && otherLeft > 0) assertThat(run.ear).isEqualTo(prevEar.other)
            prevEar = run.ear
            // Confirm at the start position (always valid).
            engine.confirm(run.initialPosition)
            runs++
        }
        assertThat(engine.isFinished).isTrue()
        assertThat(runs).isAtLeast(36)
    }

    @Test
    fun runThresholdsAreConfirmedPlusOneForDescendingAndMeanOfPair() {
        val engine = ThoroughTestEngine(lRef, Random(2), frequencies = listOf(1000))
        val b = Iso389_7.bounds(1000, lRef)
        while (true) {
            val run = engine.nextRun() ?: break
            // Descending: confirm at L_exp − 10 → threshold −69 (Lexp −60). Ascending: confirm at L_exp − 8.
            val target = if (run.kind == RunKind.DESCENDING) b.lExp - 10.0 else b.lExp - 8.0
            engine.confirm(run.positionOffset + Math.round(target - run.lMin).toInt())
        }
        val r = engine.results().getValue(PointKey(Ear.LEFT, 1000))
        assertThat(r.levelDbfs).isWithin(1e-9).of(((b.lExp - 10.0 + 1.0) + (b.lExp - 8.0)) / 2.0)
        assertThat(r.lowConfidence).isFalse()
        assertThat(r.trials).isEqualTo(2)
    }

    @Test
    fun largeDisagreementRepeatsOnceThenFlagsLowConfidence() {
        val engine = ThoroughTestEngine(lRef, Random(8), frequencies = listOf(1000))
        val b = Iso389_7.bounds(1000, lRef)
        var leftRuns = 0
        while (true) {
            val run = engine.nextRun() ?: break
            // Descending confirms 30 dB above ascending: always > 15 dB apart.
            val target = if (run.kind == RunKind.DESCENDING) b.lExp + 10.0 else b.lExp - 20.0
            if (run.ear == Ear.LEFT) leftRuns++
            engine.confirm(run.positionOffset + Math.round(target - run.lMin).toInt())
        }
        assertThat(leftRuns).isEqualTo(4) // pair + one repeat, no more
        assertThat(engine.runResults.count { it.isRepeat }).isAtLeast(2)
        val r = engine.results().getValue(PointKey(Ear.LEFT, 1000))
        assertThat(r.lowConfidence).isTrue()
        assertThat(r.trials).isEqualTo(4)
        // A good repeat clears the flag and the mean uses all four runs.
        val e2 = ThoroughTestEngine(lRef, Random(8), frequencies = listOf(1000))
        var n = 0
        while (true) {
            val run = e2.nextRun() ?: break
            val target = if (run.ear == Ear.LEFT && !run.isRepeat && run.kind == RunKind.DESCENDING) b.lExp + 10.0
            else b.lExp - 20.0
            if (run.ear == Ear.LEFT) n++
            e2.confirm(run.positionOffset + Math.round(target - run.lMin).toInt())
        }
        val r2 = e2.results().getValue(PointKey(Ear.LEFT, 1000))
        assertThat(n).isEqualTo(4)
        assertThat(r2.lowConfidence).isFalse()
        // Agreeing repeat pair: point = mean of the REPEAT pair only (desc confirmed −20 +1, asc −20).
        assertThat(r2.levelDbfs).isWithin(1e-9).of(b.lExp - 19.5)

        // persistable thorough log: contiguous seq, DESC/ASC mapped to response false/true
        val pl = e2.persistableTrials()
        assertThat(pl.map { it.seq }).isEqualTo((1..pl.size).toList())
        assertThat(pl.all { (it.run == "ASC") == it.response }).isTrue()
    }

    @Test
    fun cantHearAtMaxCensoresPoint() {
        val engine = ThoroughTestEngine(lRef, Random(6), frequencies = listOf(14000))
        var sawCensor = false
        while (true) {
            val run = engine.nextRun() ?: break
            if (run.ear == Ear.RIGHT) {
                engine.reportLimit(run.maxPosition)
                sawCensor = true
            } else {
                engine.confirm(run.initialPosition)
            }
        }
        assertThat(sawCensor).isTrue()
        val r = engine.results().getValue(PointKey(Ear.RIGHT, 14000))
        assertThat(r.censored).isTrue()
        assertThat(r.levelDbfs).isEqualTo(Iso389_7.lMax(14000, lRef))
        assertThat(engine.results().getValue(PointKey(Ear.LEFT, 14000)).censored).isFalse()
        // Reporting a limit when the dial is mid-range is rejected.
        val e2 = ThoroughTestEngine(lRef, Random(6), frequencies = listOf(1000))
        val run = checkNotNull(e2.nextRun())
        val mid = run.positionOffset + 25
        runCatching { e2.reportLimit(mid) }.let { assertThat(it.isFailure).isTrue() }
    }
}
