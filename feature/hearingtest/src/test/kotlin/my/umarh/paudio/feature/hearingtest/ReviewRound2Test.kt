package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
import my.umarh.paudio.core.model.TestMode
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Second review round of M9a (DECISIONS §27.1): minimum reaction time, Saving outcome, gate timeout, and pins. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRound2Test {
    private fun TestScope.brainScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.signalsOf(rig: Rig): MutableList<SessionSignal> {
        val out = CopyOnWriteArrayList<SessionSignal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.session.signals.collect { out += it } }
        return out
    }

    private fun Rig.testing(): SessionState.Testing? = session.state.value as? SessionState.Testing

    /** Steps (5 ms) until a NEW Quick trial is open and its stimulus is under the head; returns whether it is a catch. */
    private fun TestScope.stepToQuickOnset(rig: Rig, afterPlays: Int): Boolean {
        advanceUntil(stepMs = 5) { rig.testing()?.trialOpen == true && rig.tone.playing.value && rig.tone.played.size > afterPlays }
        return rig.tone.played.last().info().silent
    }

    // ---- SHOULD-FIX 1: minimum reaction time -------------------------------------------------------------------

    /** At onset and onset + 150 ms an answer is ignored (trial stays open); at onset + 250 ms it counts. */
    private fun TestScope.assertMinimumReactionTime(rig: Rig, signals: MutableList<SessionSignal>) {
        val before = signals.size
        rig.session.respond(true) // a re-tap landing on the onset
        runCurrent()
        assertThat(rig.testing()!!.trialOpen).isTrue()
        advanceTimeBy(150)
        runCurrent()
        rig.session.respond(false) // "I didn't" too fast is ignored as well
        runCurrent()
        assertThat(rig.testing()!!.trialOpen).isTrue()
        assertThat(signals.size).isEqualTo(before)
        advanceTimeBy(100) // onset + 250 ms
        runCurrent()
        rig.session.respond(true)
        runCurrent()
        assertThat(signals.size).isEqualTo(before + 1)
        assertThat(rig.testing()!!.trialOpen).isFalse()
    }

    @Test
    fun quick_answersBeforeOnsetPlus200msAreIgnored_forRealAndCatchTrials() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 3, earlyMinCatches = 100_000)
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        val signals = signalsOf(rig)
        rig.session.start()
        var sawReal = false
        var sawCatch = false
        var guard = 0
        while (!(sawReal && sawCatch) && guard++ < 40) {
            val isCatch = stepToQuickOnset(rig, rig.tone.played.size)
            if (isCatch && !sawCatch) {
                assertMinimumReactionTime(rig, signals)
                sawCatch = true
            } else if (!isCatch && !sawReal) {
                assertMinimumReactionTime(rig, signals)
                sawReal = true
            } else {
                advanceTimeBy(300)
                runCurrent()
                rig.session.respond(false)
                runCurrent()
            }
        }
        assertThat(sawReal).isTrue()
        assertThat(sawCatch).isTrue()
        rig.session.cancelAndJoin()
    }

    @Test
    fun thorough_confirmAndLimitCountOnlyFrom200msAfterTheRunsFirstPulse() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.THOROUGH, timed = true, seed = 5)
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoThorough = false
        brain.install()
        val signals = signalsOf(rig)
        rig.session.start()
        advanceUntil(stepMs = 5) { rig.testing()?.dial != null && rig.tone.playing.value }
        val dial = rig.testing()!!.dial!!
        rig.session.dialConfirm() // at the first pulse's onset
        runCurrent()
        assertThat(rig.testing()!!.dial).isNotNull()
        rig.session.dialTo(dial.maxPosition)
        advanceTimeBy(100)
        runCurrent()
        rig.session.dialLimit() // at the top, but only 100 ms after the onset
        runCurrent()
        assertThat(rig.testing()!!.dial).isNotNull()
        assertThat(signals).isEmpty()
        advanceTimeBy(150) // onset + 250 ms
        runCurrent()
        rig.session.dialLimit()
        advanceTimeBy(100)
        runCurrent()
        assertThat(signals).containsExactly(SessionSignal.RunConfirmed)
        rig.session.cancelAndJoin()
    }

    // ---- NIT: exit during Saving ---------------------------------------------------------------------------

    @Test
    fun exitDuringSavingEndsFinishedWhenTheTestWasStored() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = false, seed = 3, earlyMinCatches = 100_000)
        val gate = CompletableDeferred<Unit>()
        rig.store.saveGate = gate
        Brain(rig, normalListener()).install()
        rig.session.start()
        advanceUntil { rig.session.state.value == SessionState.Saving && rig.store.attempts.get() == 1 }
        rig.session.cancel() // Exit while the store is writing
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(rig.store.saved).hasSize(1)
        assertThat(rig.session.state.value).isEqualTo(SessionState.Finished(42L))
        assertThat(rig.tone.isOpen).isFalse()
        assertThat(rig.bypass.bypassed.value).isFalse()
        assertThat(rig.volume.index()).isEqualTo(rig.originalVolume)
    }

    // ---- NIT: gate timeout --------------------------------------------------------------------------------

    @Test
    fun aWedgedPreviousSessionEndsInTryAgainAfterTheGateTimeout() = runTest {
        val gate = SessionGate()
        val first = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, earlyMinCatches = 100_000, gate = gate)
        val brain = Brain(first, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        first.session.start()
        advanceUntil { first.testing()?.trialOpen == true }
        val second = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 4, earlyMinCatches = 100_000, gate = gate)
        second.session.start()
        advanceTimeBy(SessionConfig.DEFAULT_GATE_TIMEOUT_MS - 100)
        runCurrent()
        assertThat(second.session.state.value).isEqualTo(SessionState.Starting)
        advanceTimeBy(200)
        runCurrent()
        assertThat(second.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.PREVIOUS_SESSION_BUSY))
        assertThat(second.trace.acquired.get()).isEqualTo(0)
        assertThat(second.tone.openCount.get()).isEqualTo(0)
        first.session.cancelAndJoin()
    }

    // ---- pins: liveness flags with the job still active ------------------------------------------------------

    @Test
    fun aResumeInsideTheCleanupNeverReRaisesTheVolumeOrRetakesFocus() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, earlyMinCatches = 100_000)
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        advanceUntil { rig.testing()?.trialOpen == true }
        rig.volume.externalChange(3)
        runCurrent()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Paused::class.java)
        // Runs INSIDE the cleanup of an Exit, after the volume restore (without the guards it would re-lock the volume
        // that VolumeLock has just released, for good, and re-take focus).
        var activeAtHook = false
        rig.focus.onAbandon = {
            activeAtHook = rig.session.isActive
            rig.session.resumeNow()
        }
        rig.session.cancelAndJoin()
        assertThat(rig.volume.index()).isEqualTo(3)
        assertThat(rig.focus.held).isFalse()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.USER))
        // Exit cancels the job, so isActive is already false here; the timeout test below covers the active-job window
        assertThat(activeAtHook).isFalse()
    }

    @Test
    fun aResumeWhileThePauseTimeoutUnwindsIsIgnored_jobStillActive() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, pauseTimeoutMs = 60_000, earlyMinCatches = 100_000)
        val brain = Brain(rig, normalListener(), scope = brainScope())
        brain.autoQuick = false
        brain.install()
        rig.session.start()
        advanceUntil { rig.testing()?.trialOpen == true }
        rig.volume.externalChange(3)
        runCurrent()
        val requests = rig.focus.requests.get()
        var activeAtHook = false
        // The focus monitor is cancelled while the PAUSE_TIMEOUT abort unwinds, before the cleanup starts: the job is
        // still active and the cleanup has not started, so only resumeClosed stops this resume.
        rig.focus.onMonitorStopped = {
            activeAtHook = rig.session.isActive
            rig.session.resumeNow()
        }
        advanceTimeBy(61_000)
        advanceUntilIdle()
        assertThat(activeAtHook).isTrue()
        assertThat(rig.session.state.value).isEqualTo(SessionState.Aborted(AbortReason.PAUSE_TIMEOUT))
        assertThat(rig.focus.requests.get()).isEqualTo(requests)
        assertThat(rig.volume.index()).isEqualTo(3)
    }

    // ---- pins: emitWhenSilent, unconditional stopCurrent ----------------------------------------------------

    @Test
    fun signalsWaitForALaggingStimulusFlag() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 21, earlyMinCatches = 100_000)
        val tone = rig.tone as ScriptedTone
        tone.tailScope = backgroundScope.let { CoroutineScope(it.coroutineContext + StandardTestDispatcher(testScheduler)) }
        tone.tailMs = 100 // the flag trails the audio by 100 ms
        Brain(rig, normalListener(21), scope = brainScope(), heardMs = 300, notHeardMs = 1575).install()
        val audibleAtSignal = CopyOnWriteArrayList<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.signals.collect { audibleAtSignal += rig.session.isPulseAudibleNow || rig.tone.playing.value }
        }
        rig.session.start()
        advanceUntil { audibleAtSignal.size >= 20 }
        assertThat(audibleAtSignal).doesNotContain(true)
        rig.session.cancelAndJoin()
    }

    @Test
    fun everyQuickAnswerRampsTheStimulusDownEvenWhenTheFlagReadsSilent() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 22, earlyMinCatches = 100_000)
        // every answer arrives after the 1075 ms stimulus ended: `playing` reads false at that moment
        Brain(rig, normalListener(22), scope = brainScope(), heardMs = 1_500, notHeardMs = 1_575).install()
        val signals = signalsOf(rig)
        rig.session.start()
        advanceUntil { signals.size >= 20 }
        rig.session.cancelAndJoin()
        val responded = signals.count { it is SessionSignal.Responded }
        assertThat((rig.tone as ScriptedTone).stopCalls.get()).isAtLeast(responded)
    }

    // ---- SHOULD-FIX 3 (decided): no trial-locked visual state in Quick -----------------------------------------

    @Test
    fun quickPublishesNoVisibleChangeAtAStimulusOnset() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 3, earlyMinCatches = 100_000)
        Brain(rig, normalListener(), scope = brainScope(), heardMs = 600, notHeardMs = 1575).install()
        val states = CopyOnWriteArrayList<Pair<SessionState, Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.state.collect { states += it to (rig.session.isPulseAudibleNow || rig.tone.playing.value) }
        }
        rig.session.start()
        advanceUntil { states.count { (s, _) -> s is SessionState.Testing && s.trialOpen } >= 30 }
        rig.session.cancelAndJoin()
        val testing = states.filter { it.first is SessionState.Testing }
        // the test screen is up before the first stimulus (not swapped in at its onset)
        assertThat(testing.first().second).isFalse()
        assertThat((testing.first().first as SessionState.Testing).trialOpen).isFalse()
        // at every onset only the invisible trialOpen flag changes
        for ((a, b) in testing.zipWithNext()) {
            val x = a.first as SessionState.Testing
            val y = b.first as SessionState.Testing
            if (!x.trialOpen && y.trialOpen) assertThat(y.copy(trialOpen = false)).isEqualTo(x)
        }
    }
}
