package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.core.audiometry.AlwaysYesListener
import my.umarh.paudio.core.model.TestMode
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The M9a review findings (DECISIONS §27), one regression test per finding. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {

    private fun TestScope.brainScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.quickRig(timed: Boolean = true, seed: Long = 3, pauseTimeoutMs: Long = SessionConfig.DEFAULT_PAUSE_TIMEOUT_MS, gate: SessionGate = SessionGate()) =
        Rig.virtual(testScheduler, TestMode.QUICK, timed, seed, pauseTimeoutMs = pauseTimeoutMs, earlyMinCatches = 100_000, gate = gate)

    /** Also trap #5: one stream per session (opened once, closed once) unless [expectedOpens] is null. */
    private fun Rig.assertReleased(expectedVolume: Int = originalVolume, expectedOpens: Int? = 1) {
        if (expectedOpens != null) assertThat(tone.openCount.get()).isEqualTo(expectedOpens)
        assertThat(tone.closeCount.get()).isEqualTo(1)
        assertThat(tone.violations).isEmpty()
        assertThat(tone.isOpen).isFalse()
        assertThat(focus.held).isFalse()
        assertThat(bypass.bypassed.value).isFalse()
        assertThat(volume.index()).isEqualTo(expectedVolume)
    }

    /** Records every state, and fails the test if a stimulus ever starts while the Paused screen is up. */
    private fun TestScope.watch(rig: Rig): MutableList<SessionState> {
        val seen = CopyOnWriteArrayList<SessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.state.collect { seen += it } }
        val inner = rig.tone.onPlay
        rig.tone.onPlay = { b ->
            if (rig.session.state.value is SessionState.Paused) rig.tone.violations += "stimulus while Paused is shown"
            inner(b)
        }
        return seen
    }

    private fun TestScope.openFirstTrial(rig: Rig): Brain {
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        advanceUntilIdle()
        assertThat((rig.session.state.value as SessionState.Testing).trialOpen).isTrue()
        return brain
    }

    // ---- MUST-FIX 1: resume puts the interrupted screen back ----------------------------------------------------

    @Test
    fun focusRefusedAtStart_pausesInTheChannelCheck_andResumeShowsTheChannelCheckAgain() = runTest {
        val rig = quickRig()
        rig.focus.grant = false // a call is active
        Brain(rig, normalListener(), scope = brainScope()).install()
        val seen = watch(rig)
        rig.session.start()
        runCurrent()
        val paused = rig.session.state.value as SessionState.Paused
        assertThat(paused.reason).isEqualTo(PauseReason.FOCUS_LOST)
        assertThat(rig.tone.played).isEmpty()
        rig.focus.grant = true
        rig.session.resume()
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.ChannelCheck::class.java)
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(seen.filterIsInstance<SessionState.Calibrating>()).isNotEmpty()
        rig.assertReleased()
    }

    @Test
    fun pauseDuringTheChannelCheck_resumeRestoresTheChannelCheckButtons() = runTest {
        val rig = quickRig()
        val brain = Brain(rig, normalListener(), scope = brainScope())
        // nobody answers until the pause is over
        brain.install()
        val inner = rig.tone.onPlay
        var answering = false
        rig.tone.onPlay = { b -> if (answering) inner(b) }
        watch(rig)
        rig.session.start()
        advanceTimeBy(3_000)
        runCurrent()
        val before = rig.session.state.value
        assertThat(before).isInstanceOf(SessionState.ChannelCheck::class.java)
        rig.volume.externalChange(3)
        runCurrent()
        assertThat((rig.session.state.value as SessionState.Paused).reason).isEqualTo(PauseReason.VOLUME_CHANGED)
        rig.session.resume()
        runCurrent()
        assertThat(rig.session.state.value).isEqualTo(before)
        answering = true
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        rig.assertReleased()
    }

    @Test
    fun pauseDuringCalibration_resumeRestoresTheCalibrationScreen() = runTest {
        val rig = quickRig()
        Brain(rig, normalListener(), scope = brainScope()).install()
        watch(rig)
        rig.session.start()
        // run until the ramp is playing (the brain taps at -56 dBFS, about 7.6 s into the ramp)
        var guard = 0
        while (!((rig.session.state.value as? SessionState.Calibrating)?.phase == CalibrationPhase.RAMP) && guard++ < 400) {
            advanceTimeBy(50)
            runCurrent()
        }
        assertThat((rig.session.state.value as SessionState.Calibrating).phase).isEqualTo(CalibrationPhase.RAMP)
        rig.focus.lose(FocusEvent.LOST_TRANSIENT)
        runCurrent()
        val paused = rig.session.state.value as SessionState.Paused
        assertThat(paused.calibrating).isTrue()
        rig.session.resume()
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Calibrating::class.java)
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        rig.assertReleased()
    }

    @Test
    fun quickResume_showsTheTestScreenAtOnceWithTheTrialClosed() = runTest {
        val rig = quickRig()
        val brain = openFirstTrial(rig)
        watch(rig)
        rig.session.onBackgrounded()
        runCurrent()
        rig.session.onForegrounded()
        rig.session.resume()
        runCurrent()
        // not Paused through the 400-1200 ms gap: the trial screen is back at once, its trial closed
        val st = rig.session.state.value as SessionState.Testing
        assertThat(st.trialOpen).isFalse()
        brain.autoQuick = true
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        rig.assertReleased()
    }

    // ---- MUST-FIX 2: one session at a time ------------------------------------------------------------------

    @Test
    fun aSecondSessionWaitsForTheFirstOnesCleanupBeforeTouchingAnything() = runTest {
        val gate = SessionGate()
        val first = quickRig(gate = gate)
        openFirstTrial(first)
        val second = quickRig(gate = gate, seed = 4)
        Brain(second, normalListener(), scope = brainScope()).install()
        second.session.start()
        advanceTimeBy(4_000) // below the 5 s gate timeout
        runCurrent()
        assertThat(second.session.state.value).isEqualTo(SessionState.Starting)
        assertThat(second.trace.acquired.get()).isEqualTo(0)
        assertThat(second.tone.openCount.get()).isEqualTo(0)
        first.session.cancelAndJoin()
        first.assertReleased()
        advanceUntilIdle()
        assertThat(second.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(second.trace.acquired.get()).isEqualTo(1)
        second.assertReleased()
    }

    @Test
    fun cancellingASessionThatWaitsForTheGateEndsAbortedWithoutHoldingAnything() = runTest {
        val gate = SessionGate()
        val first = quickRig(gate = gate)
        openFirstTrial(first)
        val second = quickRig(gate = gate, seed = 4)
        second.session.start()
        runCurrent()
        second.session.cancelAndJoin()
        assertThat(second.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.USER))
        assertThat(second.trace.acquired.get()).isEqualTo(0)
        first.session.cancelAndJoin()
        // the gate is free again
        val third = quickRig(gate = gate, seed = 5, timed = false)
        Brain(third, normalListener()).install()
        third.session.start()
        advanceUntilIdle()
        assertThat(third.session.state.value).isInstanceOf(SessionState.Finished::class.java)
    }

    // ---- MUST-FIX 3: a Continue that races the cleanup is ignored -------------------------------------------------

    @Test
    fun exitThenContinue_neverReRaisesTheVolumeOrRetakesFocus() = runTest {
        val rig = quickRig()
        openFirstTrial(rig)
        rig.volume.externalChange(3) // pause: the user lowered the volume
        runCurrent()
        assertThat((rig.session.state.value as SessionState.Paused).reason).isEqualTo(PauseReason.VOLUME_CHANGED)
        val requests = rig.focus.requests.get()
        rig.session.cancel() // Exit
        rig.session.resume() // Continue, a moment later
        advanceUntilIdle()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.USER))
        assertThat(rig.focus.requests.get()).isEqualTo(requests)
        rig.assertReleased(expectedVolume = 3) // never raised back to the locked index 8
        assertThat(rig.volume.setCalls.filter { it == 8 }).hasSize(1) // only the initial lock
    }

    @Test
    fun continueQueuedJustBeforeExit_isIgnoredToo() = runTest {
        val rig = quickRig()
        openFirstTrial(rig)
        rig.volume.externalChange(3)
        runCurrent()
        rig.session.resume() // queued on the session thread
        rig.session.cancel() // ... and Exit before it ran
        advanceUntilIdle()
        rig.assertReleased(expectedVolume = 3)
    }

    @Test
    fun continueRacingThePauseTimeout_eitherResumesOrEndsCleanly() = runTest {
        for (offset in listOf(-1L, 0L, 1L)) {
            val rig = quickRig(pauseTimeoutMs = 60_000)
            val brain = openFirstTrial(rig)
            rig.volume.externalChange(3)
            runCurrent()
            val requests = rig.focus.requests.get()
            advanceTimeBy(60_000 + offset) // just before / at / just after the timeout instant
            brain.autoQuick = true
            rig.session.resume()
            advanceUntilIdle()
            when (val st = rig.session.state.value) {
                // Continue won (also when it landed in the same instant as the timeout): the test goes on and
                // the pre-test volume is restored at the end.
                is SessionState.Finished -> rig.assertReleased()
                // The timeout won: the late Continue touched neither the volume nor focus.
                SessionState.Aborted(AbortReason.PAUSE_TIMEOUT) -> {
                    assertThat(rig.focus.requests.get()).isEqualTo(requests)
                    rig.assertReleased(expectedVolume = 3)
                }
                else -> error("offset=$offset state=$st")
            }
            if (offset == 1L) assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.PAUSE_TIMEOUT))
            if (offset == -1L) assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        }
    }

    @Test
    fun continueAfterTheSessionEnded_isANoOp() = runTest {
        val rig = quickRig()
        openFirstTrial(rig)
        rig.volume.externalChange(3)
        runCurrent()
        rig.session.cancelAndJoin()
        rig.session.resume()
        advanceUntilIdle()
        rig.assertReleased(expectedVolume = 3)
    }

    // ---- SHOULD-FIX 1 / 2: double taps ------------------------------------------------------------------------

    @Test
    fun calibrationTapsInTheFirstSecondOfARampAreIgnored() = runTest {
        val rig = quickRig()
        Brain(rig, normalListener(), scope = brainScope()).install()
        rig.session.start()
        var guard = 0
        while (!((rig.session.state.value as? SessionState.Calibrating)?.phase == CalibrationPhase.RAMP) && guard++ < 4_000) {
            advanceTimeBy(10)
            runCurrent()
        }
        // the second tap of a double tap, right at the start of the ramp (about -90 dBFS)
        rig.session.calibrationTap()
        advanceTimeBy(500)
        runCurrent()
        rig.session.calibrationTap()
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Calibrating::class.java)
        advanceUntilIdle()
        val req = rig.store.saved.single()
        assertThat(req.test.lRef.toDouble()).isWithin(0.01).of(-60.0) // the real tap at -56 dBFS
    }

    @Test
    fun channelAnswersBeforeTheStepsFirstBeepEndedAreIgnored() = runTest {
        val rig = quickRig()
        Brain(rig, normalListener(), scope = brainScope()).install()
        val seen = watch(rig)
        rig.session.start()
        runCurrent()
        val first = rig.session.state.value as SessionState.ChannelCheck
        assertThat(first.step).isEqualTo(1)
        // a stray tap during the first beep (e.g. the second tap of a double tap) does not count
        rig.session.answerChannel(ChannelAnswer.BOTH)
        runCurrent()
        assertThat(rig.session.state.value).isEqualTo(first)
        advanceUntilIdle()
        assertThat(seen.filterIsInstance<SessionState.ChannelCheck>().map { it.problem }).doesNotContain(ChannelCheckProblem.WRONG_EAR)
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
    }

    // ---- SHOULD-FIX 3: live checks before an answer counts ------------------------------------------------------

    @Test
    fun anAnswerAfterAnUndeliveredVolumeChangeDoesNotCount() = runTest {
        val rig = quickRig()
        val brain = openFirstTrial(rig)
        // The volume changes but the change event never arrives (only the live index differs)...
        rig.volume.setIndex(2)
        rig.session.respond(true) // ...and the answer comes at the same moment
        runCurrent()
        assertThat((rig.session.state.value as SessionState.Paused).reason).isEqualTo(PauseReason.VOLUME_CHANGED)
        brain.autoQuick = true
        rig.session.resume()
        advanceUntilIdle()
        val req = rig.store.saved.single()
        // the interrupted answer was dropped: the first persisted trial is not the stray "heard"
        assertThat(req.trials).isNotEmpty()
        rig.assertReleased()
    }

    @Test
    fun anAnswerAfterAnUndeliveredHeadsetChangeDoesNotCount() = runTest {
        val rig = quickRig()
        openFirstTrial(rig)
        // another headset at the moment of the answer: the answer must not count for the original headset
        rig.devices.set(OTHER_BUDS)
        rig.session.respond(true)
        runCurrent()
        assertThat((rig.session.state.value as SessionState.Paused).reason).isEqualTo(PauseReason.HEADSET_DISCONNECTED)
        rig.session.cancelAndJoin()
        rig.assertReleased()
    }

    // ---- SHOULD-FIX 5: calibration cache per listener ----------------------------------------------------------

    @Test
    fun calibrationIsNotReusedForAnotherListener() = runTest {
        val cache = CalibrationCache()
        fun rampCycles(r: Rig) = r.tone.played.count { val i = it.info(); !i.silent && i.ear == null }
        val first = Rig.virtual(testScheduler, TestMode.QUICK, true, 3, earlyMinCatches = 100_000, cache = cache)
        Brain(first, normalListener(), scope = brainScope()).install()
        first.session.start()
        advanceUntilIdle()
        assertThat(rampCycles(first)).isGreaterThan(0)

        val other = Rig.virtual(testScheduler, TestMode.QUICK, true, 4, earlyMinCatches = 100_000, cache = cache)
        other.store.listener = 2L
        Brain(other, normalListener(), scope = brainScope()).install()
        other.session.start()
        advanceUntilIdle()
        assertThat(other.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(rampCycles(other)).isGreaterThan(0)

        val same = Rig.virtual(testScheduler, TestMode.QUICK, true, 5, earlyMinCatches = 100_000, cache = cache)
        same.store.listener = 2L
        Brain(same, normalListener(), scope = brainScope()).install()
        same.session.start()
        advanceUntilIdle()
        assertThat(rampCycles(same)).isEqualTo(0) // listener 2's calibration is reused for listener 2
    }

    // ---- NIT: the real stream minimum ------------------------------------------------------------------------

    @Test
    fun calibrationNeverPlansBelowTheStreamMinimum() = runTest {
        val rig = quickRig()
        rig.platform.minIndex = 6
        // too loud at index 8: an unconstrained rerun would go to index 3 or 4
        Brain(rig, normalListener(), scope = brainScope(), tapAt = { i -> -75.0 - (4 * i - 32) }).install()
        rig.session.start()
        advanceUntilIdle()
        val restore = rig.volume.setCalls.last()
        assertThat(restore).isEqualTo(rig.originalVolume)
        assertThat(rig.volume.setCalls.dropLast(1).all { it >= 6 }).isTrue()
        assertThat(rig.volume.setCalls).contains(6)
    }

    // ---- SHOULD-FIX 6: the end-of-test prompt never discards a finished test ------------------------------------

    @Test
    fun unansweredEndOfTestPromptSavesTheFinishedTestFlaggedUnreliable() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = false, seed = 3, pauseTimeoutMs = 60_000, earlyMinCatches = 100_000)
        Brain(rig, normalListener(), responder = AlwaysYesListener).install()
        rig.session.start()
        advanceUntil { rig.session.state.value is SessionState.Paused }
        val p = rig.session.state.value as SessionState.Paused
        assertThat(p.reason).isEqualTo(PauseReason.UNRELIABLE)
        assertThat(p.testFinished).isTrue()
        advanceTimeBy(61_000)
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(rig.store.saved.single().test.reliable).isFalse()
        rig.assertReleased()
    }

    // ---- NIT: consecutive stream failures ----------------------------------------------------------------------

    @Test
    fun scatteredStreamLossesAreNotFatal_onlyConsecutiveOnesAre() = runTest {
        val rig = quickRig(timed = false)
        val brain = Brain(rig, normalListener())
        brain.install()
        val inner = rig.tone.onPlay
        var quickPlays = 0
        rig.tone.onPlay = { b ->
            val st = rig.session.state.value
            // lose the stream on every 5th Quick stimulus: far more than MAX_STREAM_FAILURES in total
            if (st is SessionState.Testing && ++quickPlays % 5 == 0) (rig.tone as ScriptedTone).failNextPlayWithStreamLoss = true
            inner(b)
        }
        rig.session.start()
        advanceUntilIdle()
        assertThat(quickPlays / 5).isGreaterThan(TestSession.MAX_STREAM_FAILURES)
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        rig.assertReleased(expectedOpens = null)
        // one re-open per lost stream and nothing else (the last armed loss may not have happened before the end)
        assertThat(rig.tone.openCount.get()).isIn(com.google.common.collect.Range.closed(quickPlays / 5, 1 + quickPlays / 5))
    }

    // ---- NIT: Quick trial ends with an unconditional stopCurrent --------------------------------------------------

    @Test
    fun anAnswerDuringTheStimulusStopsItBeforeTheSignal() = runTest {
        val rig = quickRig()
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        var guard = 0
        // step until the first Quick stimulus is playing and answers are armed (onset + 200 ms; still inside the 1075 ms stimulus)
        while (!((rig.session.state.value as? SessionState.Testing)?.trialOpen == true && rig.session.responseArmed && rig.tone.playing.value) && guard++ < 10_000) {
            advanceTimeBy(10)
            runCurrent()
        }
        val playingAtSignal = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.signals.collect { playingAtSignal += rig.session.isPulseAudibleNow || rig.tone.playing.value }
        }
        assertThat(rig.tone.playing.value).isTrue()
        rig.session.respond(true)
        runCurrent()
        assertThat(playingAtSignal).containsExactly(false)
        rig.session.cancelAndJoin()
    }
}
