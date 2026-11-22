package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineMode
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.core.audiometry.AlwaysYesListener
import my.umarh.paudio.core.audiometry.Calibration
import my.umarh.paudio.core.audiometry.Iso389_7
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionTest {

    private fun TestScope.quickRig(timed: Boolean = false, seed: Long = 3, early: Int = 100_000, cache: CalibrationCache = CalibrationCache()) =
        Rig.virtual(testScheduler, TestMode.QUICK, timed, seed, earlyMinCatches = early, cache = cache)

    /** Foreground scope on the test scheduler (backgroundScope tasks are not run by advanceUntilIdle). */
    private fun TestScope.brainScope() = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))

    /**
     * [expectedVolume]: VolumeLock never RAISES a volume the user lowered meanwhile (hearing safety).
     * [expectedOpens]: trap #5 (one continuous stream per session, DECISIONS §23/§26.3): the stream is opened exactly
     * once, also across pauses and resumes; only a LOST stream is re-opened. It is closed exactly once, in the cleanup.
     * null skips the count (sessions cancelled at a random point).
     */
    private fun Rig.assertCleanedUp(expectedVolume: Int = originalVolume, expectedOpens: Int? = 1) {
        assertThat(tone.violations).isEmpty()
        assertThat(tone.isOpen).isFalse()
        assertThat(focus.held).isFalse()
        assertThat(bypass.bypassed.value).isFalse()
        assertThat(volume.index()).isEqualTo(expectedVolume)
        if (expectedOpens != null) {
            assertThat(tone.openCount.get()).isEqualTo(expectedOpens)
            assertThat(tone.closeCount.get()).isEqualTo(1)
        }
        if (tone.openCount.get() > 0) {
            assertThat(log.indexOf("tone.close")).isLessThan(log.indexOf("bypass.release"))
            assertThat(log.indexOf("bypass.acquire")).isLessThan(log.indexOf("tone.open"))
        }
    }

    private fun Rig.finished(): SessionState.Finished = (session.state.value as? SessionState.Finished ?: error("state=${session.state.value} log=${log.events} err=${session.lastFailure?.stackTraceToString()?.take(900)}"))

    // ---- happy paths + P0 ------------------------------------------------------------------------------------

    @Test
    fun quickHappyPathReachesSaveAndKeepsBypassAroundEveryTone() = runTest {
        val rig = quickRig()
        Brain(rig, normalListener()).install()
        rig.session.start()
        advanceUntilIdle()

        rig.finished()
        val req = rig.store.saved.single()
        assertThat(req.test.mode).isEqualTo(TestMode.QUICK)
        assertThat(req.thresholds).hasSize(18)
        assertThat(req.test.lRef.toDouble()).isWithin(0.01).of(-60.0)
        assertThat(req.test.volIndex).isEqualTo(Calibration.initialIndex(15))
        assertThat(req.test.volMax).isEqualTo(15)
        assertThat(req.test.ancOn).isTrue()
        assertThat(req.test.algoVersion).isEqualTo(TestSession.ALGO_VERSION)
        assertThat(req.test.appVersion).isEqualTo("1.2.3-test")
        assertThat(req.test.reliable).isTrue()
        assertThat(req.test.fpRate).isNotNull()
        assertThat(req.test.deviceSnapshotJson).contains("WIRED")
        assertThat(req.device).isEqualTo(FakeOutputDevices.WIRED_HEADPHONES)
        assertThat(req.trials.map { it.seq }).isEqualTo((1..req.trials.size).toList())
        val catches = req.trials.filter { it.isCatch }
        assertThat(catches).isNotEmpty()
        assertThat(catches.all { it.levelDbfs == null }).isTrue()
        // P0: one token for the whole flow, no tone without it, closed in the right order.
        assertThat(rig.trace.acquired.get()).isEqualTo(1)
        assertThat(rig.focus.abandoned.get()).isEqualTo(1)
        // no level above L_max, the opposite channel silent
        for (b in rig.tone.played) {
            val info = b.info()
            if (!info.silent) assertThat(info.levelDbfs).isAtMost(-3.0 + 1e-6)
        }
        rig.assertCleanedUp()
        assertThat(rig.log.events.first()).isEqualTo("bypass.acquire")
        assertThat(rig.log.events.last()).isEqualTo("bypass.release")
    }

    @Test
    fun thoroughHappyPathWithSimulatedConfirmer() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.THOROUGH, timed = true, seed = 5)
        val listener = normalListener(11)
        Brain(rig, listener, scope = brainScope(), thoroughRunMs = 12_000).install()
        rig.session.start()
        advanceUntilIdle()

        rig.finished()
        val req = rig.store.saved.single()
        assertThat(req.test.mode).isEqualTo(TestMode.THOROUGH)
        assertThat(req.thresholds).hasSize(18)
        assertThat(req.trials.all { it.run == "DESC" || it.run == "ASC" }).isTrue()
        assertThat(req.test.fpRate).isNull()
        // the confirmer is accurate: within 6 dB of the true threshold for uncensored points
        var checked = 0
        for (t in req.thresholds) {
            if (t.censored) continue
            val truth = listener.trueThreshold(t.ear, t.freqHz)
            assertThat(kotlin.math.abs(t.levelDbfs!! - truth)).isLessThan(8.0)
            checked++
        }
        assertThat(checked).isAtLeast(14)
        rig.assertCleanedUp()
    }

    @Test
    fun p0WithTheRealEffectEngine_noEnabledEffectWhileTonesAreActive() = runTest {
        val backend = FakeEffectBackend()
        val source = FakeSessionSource()
        val engine = EffectEngine(
            backend,
            { source },
            { false },
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            { testScheduler.currentTime },
        )
        engine.setOutputDevice(FakeOutputDevices.WIRED_HEADPHONES)
        engine.setTarget(EqConfig.flat())
        engine.setRunning(true)
        source.open(5, "player.app")
        assertThat(backend.activeEnabled).hasSize(1)

        val rig = Rig(
            TestMode.QUICK, { testScheduler.currentTime }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            timed = false, seed = 9, bypassOverride = engine,
        )
        (rig.tone as ScriptedTone).guard = { if (backend.activeEnabled.isNotEmpty()) "enabled effect handle exists" else null }
        Brain(rig, normalListener()).install()
        rig.session.start()
        advanceUntilIdle()

        rig.finished()
        assertThat(rig.tone.violations).isEmpty()
        assertThat(rig.tone.played).isNotEmpty()
        // restored afterwards
        assertThat(engine.bypassed.value).isFalse()
        assertThat(backend.activeEnabled).hasSize(1)
    }

    // ---- cancellation (virtual time; the real-thread version is RealThreadCancellationTest) -------------------

    @Test
    fun cancelAtRandomPointsAlwaysClosesTrackRestoresVolumeAndReleasesToken() = runTest {
        val rng = Random(20260)
        repeat(240) { i ->
            val mode = if (i % 3 == 0) TestMode.THOROUGH else TestMode.QUICK
            val rig = Rig.virtual(testScheduler, mode, timed = true, seed = i.toLong())
            val bs = brainScope()
            Brain(rig, normalListener(i.toLong()), scope = bs, heardMs = 1000, notHeardMs = 1575, thoroughRunMs = 12_000).install()
            rig.session.start()
            var lowered = false
            val total = rng.nextLong(0, 90_000)
            // sometimes a pause is active when the cancellation arrives
            if (i % 4 == 1) {
                advanceTimeBy(total / 2)
                when (i % 3) {
                    0 -> rig.devices.set(null)
                    1 -> rig.focus.lose()
                    else -> { rig.volume.externalChange(2); lowered = true }
                }
                advanceTimeBy(total / 2)
            } else {
                advanceTimeBy(total)
            }
            runCurrent()
            rig.session.cancelAndJoin()
            bs.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            try { rig.assertCleanedUp(if (lowered) 2 else rig.originalVolume, expectedOpens = null); assertThat(rig.tone.openCount.get()).isAtMost(1) } catch (e: AssertionError) { throw AssertionError("i=$i mode=$mode total=$total state=${rig.session.state.value} setCalls=${rig.volume.setCalls} log=${rig.log.events}", e) }
            val st = rig.session.state.value
            if (st == SessionState.Aborted(AbortReason.AUDIO_ERROR)) error("i=$i mode=$mode err=${rig.session.lastFailure?.stackTraceToString()?.take(1200)}")
            if (rig.trace.acquired.get() > 0) assertThat(st).isEqualTo(SessionState.Aborted(AbortReason.USER)) // err
            assertThat(rig.trace.openCount).isEqualTo(0)
        }
    }

    @Test
    fun cancelBeforeTheCoroutineEverRanHoldsNothing() = runTest {
        val rig = quickRig()
        rig.session.start()
        rig.session.cancelAndJoin()
        assertThat(rig.trace.acquired.get()).isEqualTo(0)
        assertThat(rig.tone.openCount.get()).isEqualTo(0)
    }

    // ---- channel check, calibration --------------------------------------------------------------------------

    @Test
    fun wrongEarShowsSwappedCopyStateThenRetries() = runTest {
        val rig = quickRig()
        Brain(rig, normalListener()).also { it.wrongEarFirst = true }.install()
        val seen = mutableListOf<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.state.collect { seen += it } }
        rig.session.start()
        advanceUntilIdle()
        rig.finished()
        assertThat(seen.filterIsInstance<SessionState.ChannelCheck>().map { it.problem }).contains(ChannelCheckProblem.WRONG_EAR)
        // each ear was presented alone (the other channel exactly 0)
        val earPlays = rig.tone.played.map { it.info() }.filter { it.ear != null && !it.silent }
        assertThat(earPlays.map { it.ear }.toSet()).hasSize(2)
        rig.assertCleanedUp()
    }

    @Test
    fun outOfWindowCalibrationReruns_thenAccepts() = runTest {
        val rig = quickRig(timed = true)
        // device is too loud at index 8: tap at -75 (L_ref -79); the fake curve is 4 dB per index
        Brain(rig, normalListener(), scope = brainScope(), tapAt = { i -> -75.0 - (4 * i - 32) }).install()
        rig.session.start()
        advanceUntilIdle()
        rig.finished()
        val req = rig.store.saved.single()
        assertThat(req.test.volIndex).isIn(listOf(3, 4))
        assertThat(req.test.lRef.toDouble()).isIn(com.google.common.collect.Range.closed(-68.0, -50.0))
        rig.assertCleanedUp()
    }

    @Test
    fun cantHearAbortsAfterTheRampAndCleansUp() = runTest {
        val rig = quickRig(timed = true)
        Brain(rig, normalListener(), scope = brainScope(), tapAt = { 100.0 }).install()
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.CANT_HEAR))
        assertThat(rig.store.saved).isEmpty()
        rig.assertCleanedUp()
    }

    @Test
    fun refusedVolumeChangeAsksForTheVolumeKeysThenContinues() = runTest {
        val rig = quickRig(timed = true)
        // too quiet at index 8 (tap -45 -> L_ref -49), wants index 11, but the volume can't be raised
        Brain(rig, normalListener(), scope = brainScope(), tapAt = { i -> -45.0 - (4 * i - 32) }).install()
        rig.volume.safeLimit = 8
        rig.session.start()
        advanceUntilIdle()
        val st = rig.session.state.value as SessionState.Calibrating
        assertThat(st.phase).isEqualTo(CalibrationPhase.MANUAL_VOLUME)
        assertThat(st.direction).isEqualTo(VolumeDirection.UP)
        rig.volume.safeLimit = 15
        rig.volume.externalChange(11) // the user's volume keys: must not pause the flow
        rig.session.manualVolumeDone()
        advanceUntilIdle()
        rig.finished()
        assertThat(rig.store.saved.single().test.volIndex).isEqualTo(11)
        rig.assertCleanedUp()
    }

    @Test
    fun calibrationIsReusedWithin30MinutesAndExpiresAfter() = runTest {
        val cache = CalibrationCache()
        val first = quickRig(timed = true, cache = cache)
        Brain(first, normalListener(), scope = brainScope()).install()
        first.session.start()
        advanceUntilIdle()
        first.finished()

        fun rampCycles(r: Rig) = r.tone.played.count { val i = it.info(); !i.silent && i.ear == null }
        assertThat(rampCycles(first)).isGreaterThan(0)

        val second = quickRig(timed = true, seed = 4, cache = cache)
        Brain(second, normalListener(), scope = brainScope()).install()
        second.session.start()
        advanceUntilIdle()
        second.finished()
        assertThat(rampCycles(second)).isEqualTo(0)
        assertThat(second.store.saved.single().test.lRef).isEqualTo(first.store.saved.single().test.lRef)

        advanceTimeBy(31 * 60_000L)
        val third = quickRig(timed = true, seed = 5, cache = cache)
        Brain(third, normalListener(), scope = brainScope()).install()
        third.session.start()
        advanceUntilIdle()
        third.finished()
        assertThat(rampCycles(third)).isGreaterThan(0)
    }

    // ---- pause / resume --------------------------------------------------------------------------------------

    /** Runs until the first Quick trial is open and waiting for an answer (the brain does not answer). */
    private fun TestScope.openFirstTrial(rig: Rig): Brain {
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        advanceUntilIdle()
        val st = rig.session.state.value
        assertThat(st).isInstanceOf(SessionState.Testing::class.java)
        assertThat((st as SessionState.Testing).trialOpen).isTrue()
        return brain
    }

    private fun Rig.paused(): SessionState.Paused = (session.state.value as? SessionState.Paused ?: error("state=${session.state.value}"))

    @Test
    fun headsetDisconnectPausesWithCopyAndResumesOnlyWhenBack() = runTest {
        val rig = quickRig(timed = true)
        val brain = openFirstTrial(rig)
        rig.devices.set(null)
        runCurrent()
        assertThat(rig.paused().reason).isEqualTo(PauseReason.HEADSET_DISCONNECTED)
        assertThat(rig.paused().canResume).isFalse()
        assertThat(rig.tone.playing.value).isFalse()
        rig.session.resume()
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Paused::class.java)
        rig.devices.set(FakeOutputDevices.WIRED_HEADPHONES)
        runCurrent()
        assertThat(rig.paused().canResume).isTrue()
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()
        rig.assertCleanedUp()
    }

    @Test
    fun differentHeadsetCountsAsDisconnected() = runTest {
        val rig = quickRig(timed = true)
        openFirstTrial(rig)
        rig.devices.set(OTHER_BUDS)
        runCurrent()
        assertThat(rig.paused().reason).isEqualTo(PauseReason.HEADSET_DISCONNECTED)
        rig.session.cancelAndJoin()
        rig.assertCleanedUp()
    }

    @Test
    fun volumeChangePausesAndResumeRestoresTheLockedIndex() = runTest {
        val rig = quickRig(timed = true)
        val brain = openFirstTrial(rig)
        rig.volume.externalChange(3)
        runCurrent()
        assertThat(rig.paused().reason).isEqualTo(PauseReason.VOLUME_CHANGED)
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()
        assertThat(rig.store.saved.single().test.volIndex).isEqualTo(8)
        rig.assertCleanedUp()
    }

    @Test
    fun refusedVolumeRestoreKeepsPausedAndAsksForTheKeys() = runTest {
        val rig = quickRig(timed = true)
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.state.collect { seen += "${testScheduler.currentTime}:$it" } }
        val brain = openFirstTrial(rig)
        rig.volume.safeLimit = 2
        rig.volume.externalChange(2)
        runCurrent()
        rig.session.resume()
        runCurrent()
        assertThat(rig.paused().volumeKeysNeeded).isTrue()
        rig.volume.safeLimit = 15
        rig.volume.externalChange(8) // user fixes it with the keys
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()
        rig.assertCleanedUp()
    }

    @Test
    fun focusLossPausesAndResumeReacquires() = runTest {
        val rig = quickRig(timed = true)
        val brain = openFirstTrial(rig)
        rig.focus.lose(FocusEvent.LOST)
        runCurrent()
        assertThat(rig.paused().reason).isEqualTo(PauseReason.FOCUS_LOST)
        rig.focus.grant = false
        rig.session.resume()
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Paused::class.java)
        rig.focus.grant = true
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()
        rig.assertCleanedUp()
    }

    @Test
    fun backgroundingPausesAndResumeNeedsForeground() = runTest {
        val rig = quickRig(timed = true)
        val brain = openFirstTrial(rig)
        rig.session.onBackgrounded()
        runCurrent()
        assertThat(rig.paused().reason).isEqualTo(PauseReason.BACKGROUNDED)
        assertThat(rig.paused().canResume).isFalse()
        rig.session.onForegrounded()
        runCurrent()
        // the copy stays until the user continues
        assertThat(rig.paused().reason).isEqualTo(PauseReason.BACKGROUNDED)
        assertThat(rig.paused().canResume).isTrue()
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()
        rig.assertCleanedUp()
    }

    @Test
    fun resumeRestartsOnlyTheInterruptedPoint() = runTest {
        val rig = quickRig(timed = false, seed = 8)
        val listener = normalListener(5)
        var interruptedAfter = -1
        var interrupted: Pair<Any?, Int>? = null
        val brain = Brain(rig, listener)
        brain.install()
        val inner = rig.tone.onPlay
        // pause during the first trial that has at least two earlier trials at the same point
        val counts = HashMap<String, Int>()
        var pending: Pair<String, Int>? = null
        rig.tone.onPlay = { b ->
            val st = rig.session.state.value
            val info = b.info()
            if (st is SessionState.Testing && st.trialOpen && !info.silent && interrupted == null) {
                val key = "${info.ear}@${rig.session.quickTrialFreq}"
                if ((counts[key] ?: 0) >= 2) {
                    interrupted = key to rig.session.quickTrialFreq
                    interruptedAfter = rig.tone.played.size
                    pending = key to (counts[key] ?: 0)
                    rig.session.onBackgrounded() // interrupts this very trial
                } else {
                    counts[key] = (counts[key] ?: 0) + 1
                    inner(b)
                }
            } else {
                if (st is SessionState.Testing && st.trialOpen && !info.silent) {
                    val key = "${info.ear}@${rig.session.quickTrialFreq}"
                    counts[key] = (counts[key] ?: 0) + 1
                }
                inner(b)
            }
        }
        rig.session.start()
        advanceUntil { rig.session.state.value is SessionState.Paused }
        assertThat(rig.paused().reason).isEqualTo(PauseReason.BACKGROUNDED)
        rig.session.onForegrounded()
        rig.session.resume()
        advanceUntilIdle()
        rig.finished()

        val req = rig.store.saved.single()
        val (key, _) = pending!!
        val lRef = req.test.lRef.toDouble()
        val persistedForKey = req.trials.filter { !it.isCatch && "${it.ear}@${it.freqHz}" == key }
        val first = persistedForKey.first()
        // The point was restarted from scratch: its first persisted trial is a fresh staircase start. The start rule
        // (HughsonWestlakeStaircase.startLevel) depends on whether the other ear's point at this frequency had
        // finished when the new staircase was created (at `first.seq`): then 15 dB above that threshold, else
        // L_exp + 20; clamped to [L_min, L_max]. Always checked.
        val b = Iso389_7.bounds(first.freqHz, lRef)
        val otherTrials = req.trials.filter { !it.isCatch && it.ear != first.ear && it.freqHz == first.freqHz }
        val otherFinishedBefore = otherTrials.isNotEmpty() && otherTrials.all { it.seq < first.seq }
        val expectedStart = if (otherFinishedBefore) {
            req.thresholds.single { it.ear != first.ear && it.freqHz == first.freqHz }.levelDbfs!!.toDouble() + 15.0
        } else {
            b.lExp + 20.0
        }.coerceIn(b.lMin, b.lMax)
        assertThat(first.levelDbfs!!.toDouble()).isWithin(0.01).of(expectedStart)
        // Exactly the presentations before the pause are gone: every persisted trial of the point was presented
        // after the resume (`counts` kept counting after the pause; the interrupted one was never counted).
        val (_, beforePause) = pending!!
        assertThat(beforePause).isAtLeast(2)
        assertThat(persistedForKey.size).isEqualTo(counts[key]!! - beforePause)
        // other points are untouched (contiguous seq)
        assertThat(req.trials.map { it.seq }).isEqualTo((1..req.trials.size).toList())
        assertThat(interruptedAfter).isGreaterThan(0)
    }

    @Test
    fun guessingMidTestPromptsAndKeepGoingSavesFlaggedUnreliable() = runTest {
        val rig = quickRig(early = 8)
        Brain(rig, normalListener(), responder = AlwaysYesListener).install()
        rig.session.start()
        advanceUntil { rig.session.state.value is SessionState.Paused }
        assertThat(rig.paused().reason).isEqualTo(PauseReason.UNRELIABLE)
        assertThat(rig.paused().testFinished).isFalse()
        rig.session.keepGoing()
        advanceUntilIdle()
        rig.finished()
        assertThat(rig.store.saved.single().test.reliable).isFalse()
        rig.assertCleanedUp()
    }

    @Test
    fun guessingPromptRedoRestartsTheTest() = runTest {
        val rig = quickRig(early = 8)
        val brain = Brain(rig, normalListener(), responder = AlwaysYesListener)
        brain.install()
        rig.session.start()
        advanceUntil { rig.session.state.value is SessionState.Paused }
        assertThat(rig.paused().reason).isEqualTo(PauseReason.UNRELIABLE)
        val playedBefore = rig.tone.played.size
        // from now on the person answers honestly
        val honest = normalListener()
        rig.tone.onPlay = Brain(rig, honest).let { b -> b.install(); rig.tone.onPlay }
        rig.session.redoTest()
        advanceUntilIdle()
        rig.finished()
        val req = rig.store.saved.single()
        assertThat(req.test.reliable).isTrue()
        assertThat(req.trials.first().seq).isEqualTo(1)
        assertThat(rig.tone.played.size).isGreaterThan(playedBefore)
        rig.assertCleanedUp()
    }

    @Test
    fun endOfTestPromptWhenEarlyCheckIsOff() = runTest {
        val rig = quickRig(early = 100_000)
        Brain(rig, normalListener(), responder = AlwaysYesListener).install()
        rig.session.start()
        advanceUntil { rig.session.state.value is SessionState.Paused }
        val p = rig.paused()
        assertThat(p.reason).isEqualTo(PauseReason.UNRELIABLE)
        assertThat(p.testFinished).isTrue()
        rig.session.keepGoing()
        advanceUntilIdle()
        rig.finished()
        assertThat(rig.store.saved.single().test.reliable).isFalse()
    }

    @Test
    fun pausedTooLongGivesTheEngineBackAndAborts() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, true, pauseTimeoutMs = 60_000)
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        runCurrent()
        rig.devices.set(null)
        advanceTimeBy(61_000)
        runCurrent()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.PAUSE_TIMEOUT))
        rig.assertCleanedUp()
    }

    // ---- failures --------------------------------------------------------------------------------------------

    @Test
    fun aLostStreamRedoesTheTrial() = runTest {
        val rig = quickRig()
        val brain = Brain(rig, normalListener())
        brain.install()
        val inner = rig.tone.onPlay
        var failed = false
        rig.tone.onPlay = { b ->
            val st = rig.session.state.value
            if (!failed && st is SessionState.Testing && st.trialOpen) {
                failed = true
            }
            inner(b)
        }
        // fail the first Quick stimulus
        var armed = false
        val orig = rig.tone.onPlay
        rig.tone.onPlay = { b ->
            val st = rig.session.state.value
            if (!armed && st is SessionState.Testing) {
                armed = true
                (rig.tone as ScriptedTone).failNextPlayWithStreamLoss = true
            }
            orig(b)
        }
        rig.session.start()
        advanceUntilIdle()
        rig.finished()
        // exactly one re-open after the lost stream (and no other open, close or per-stimulus track)
        rig.assertCleanedUp(expectedOpens = 2)
    }

    @Test
    fun aDeadStreamAbortsWithAudioError() = runTest {
        val rig = quickRig()
        (rig.tone as ScriptedTone).failAllPlays = true
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.AUDIO_ERROR))
        rig.assertCleanedUp(expectedOpens = 1 + TestSession.MAX_STREAM_FAILURES) // the first open + one re-open per allowed loss
    }

    @Test
    fun speakerOutputNeverStartsAndNeverTouchesTheEngine() = runTest {
        val rig = quickRig()
        rig.devices.set(FakeOutputDevices.SPEAKER)
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.NO_HEADPHONES))
        assertThat(rig.trace.acquired.get()).isEqualTo(0)
        assertThat(rig.tone.openCount.get()).isEqualTo(0)
    }

    @Test
    fun saveFailureKeepsTheResultsAfterCleanupAndRetrySavesThem() = runTest {
        val rig = quickRig()
        rig.store.failSave = true
        Brain(rig, normalListener()).install()
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.SaveFailed(profileMissing = false))
        rig.assertCleanedUp() // audio, volume, focus and token are released before the user decides
        // still failing: stays on SaveFailed, results kept
        rig.session.retrySave()
        assertThat(rig.session.state.value).isEqualTo(SessionState.SaveFailed(profileMissing = false))
        rig.store.failSave = false
        // a double tap on Retry stores the test once
        val a = launch { rig.session.retrySave() }
        val b = launch { rig.session.retrySave() }
        advanceUntilIdle()
        a.join(); b.join()
        assertThat(rig.store.saved).hasSize(1)
        assertThat(rig.session.state.value).isEqualTo(SessionState.Finished(42L))
        assertThat(rig.store.saved.single().thresholds).hasSize(18)
        assertThat(rig.trace.acquired.get()).isEqualTo(1) // the retry never touched the engine bypass
        rig.assertCleanedUp()
    }

    @Test
    fun aDeletedProfileIsNeverSavedElsewhereSilently() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = false, seed = 3, earlyMinCatches = 100_000, requestedProfileId = 7)
        rig.store.profileMissing = true
        Brain(rig, normalListener()).install()
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.SaveFailed(profileMissing = true))
        assertThat(rig.store.saved).isEmpty()
        rig.session.retrySave() // a plain retry still refuses
        assertThat(rig.session.state.value).isEqualTo(SessionState.SaveFailed(profileMissing = true))
        assertThat(rig.store.saved).isEmpty()
        rig.session.retrySave(useActiveProfile = true) // the user's explicit choice
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(rig.store.saved.single().requestedProfileId).isNull()
        rig.assertCleanedUp()
    }

    // ---- timing (SPEC §9 / DECISIONS §18) --------------------------------------------------------------------

    @Test
    fun quickFitsDecision18TimeBudgetWithRealisticAnswerTimes() = runTest {
        val times = (1..5).map { seed ->
            val rig = quickRig(timed = true, seed = seed.toLong())
            // heard 1.0 s, not heard 1.075 s + 0.5 s (the DECISIONS §18 model)
            Brain(rig, normalListener(seed.toLong()), scope = brainScope(), heardMs = 1000, notHeardMs = 1575).install()
            val t0 = testScheduler.currentTime
            var testStart = -1L
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                rig.session.state.collect { if (it is SessionState.Testing && testStart < 0) testStart = testScheduler.currentTime }
            }
            rig.session.start()
            advanceUntilIdle()
            rig.finished()
            job.cancel()
            (testScheduler.currentTime - testStart) / 60_000.0
        }
        println("QUICK simulated test-phase minutes: $times")
        // The spec's 6 min is unreachable by the normative procedure (DECISIONS §18); the relaxed bound is 9 min.
        for (m in times) assertThat(m).isLessThan(9.0)
    }

    @Test
    fun thoroughFitsTenMinutesAtTwelveSecondsPerRun() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.THOROUGH, true, 6)
        Brain(rig, normalListener(3), scope = brainScope(), thoroughRunMs = 12_000).install()
        var testStart = -1L
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.state.collect { if (it is SessionState.Testing && testStart < 0) testStart = testScheduler.currentTime }
        }
        rig.session.start()
        advanceUntilIdle()
        rig.finished()
        val minutes = (testScheduler.currentTime - testStart) / 60_000.0
        println("THOROUGH simulated test-phase minutes: $minutes")
        assertThat(minutes).isLessThan(10.0)
    }
}
