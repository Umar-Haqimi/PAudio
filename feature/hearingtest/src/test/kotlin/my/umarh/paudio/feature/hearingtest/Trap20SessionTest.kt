package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.audiometry.StimulusSynth
import my.umarh.paudio.core.model.TestMode
import org.junit.Test

/**
 * SPEC §2.2 trap #20, behavioural (DECISIONS §26.3 row 20, §27): every haptic / TalkBack trigger the session emits
 * ([SessionSignal]) is emitted while nothing is audible, and the Thorough dial loop leaves a silent window in every
 * pulse period so the dial's deferred haptic tick has somewhere to land. The UI side (the dial only ticks while the
 * flag is false; signal haptics and announcements wait for it) is `Trap20UiTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Trap20SessionTest {
    private fun TestScope.brainScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private class Audit {
        val signals = mutableListOf<SessionSignal>()
        val audibleAtSignal = mutableListOf<Boolean>()
    }

    /** Collects synchronously (unconfined), so the audibility is read at the moment of emission. */
    private fun TestScope.audit(rig: Rig): Audit {
        val a = Audit()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.signals.collect { s ->
                a.signals += s
                a.audibleAtSignal += rig.session.isPulseAudibleNow || rig.tone.playing.value
            }
        }
        return a
    }

    @Test
    fun quick_everySignalIsEmittedInSilence_alsoWhenTheAnswerCutsTheStimulusShort() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true, seed = 21, earlyMinCatches = 100_000)
        // "heard" after 300 ms: inside the 1075 ms stimulus, so the stimulus is ramped down early
        Brain(rig, normalListener(21), scope = brainScope(), heardMs = 300, notHeardMs = 1575).install()
        val a = audit(rig)
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(a.signals.filterIsInstance<SessionSignal.Responded>().size).isGreaterThan(100)
        assertThat(a.audibleAtSignal).doesNotContain(true)
    }

    @Test
    fun thorough_everySignalIsEmittedInSilence_andEveryPulseIsFollowedByASilentGap() = runTest {
        val rig = Rig.virtual(testScheduler, TestMode.THOROUGH, timed = true, seed = 22)
        Brain(rig, normalListener(22), scope = brainScope(), thoroughRunMs = 3_000).install()
        val a = audit(rig)
        // (time, audible) transitions of the flag the dial's haptics are gated on, while a dial run is open
        val transitions = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.session.pulseAudible.collect { v ->
                if ((rig.session.state.value as? SessionState.Testing)?.dial != null) transitions += testScheduler.currentTime to v
            }
        }
        rig.session.start()
        advanceUntilIdle()
        assertThat(rig.session.state.value).isInstanceOf(SessionState.Finished::class.java)
        assertThat(a.signals.filterIsInstance<SessionSignal.RunConfirmed>()).isNotEmpty()
        assertThat(a.audibleAtSignal).doesNotContain(true)

        // Silent windows: after each pulse the flag drops for (about) the whole 200 ms gap before the next pulse.
        val gaps = transitions.zipWithNext().filter { (x, y) -> !x.second && y.second }.map { (x, y) -> y.first - x.first }
        assertThat(gaps.size).isGreaterThan(50)
        assertThat(gaps.count { it >= StimulusSynth.GAP_MS - 1 }).isAtLeast(gaps.size * 9 / 10)
        // ...and each audible stretch is one pulse (225 ms), never the whole 425 ms period
        val pulses = transitions.zipWithNext().filter { (x, y) -> x.second && !y.second }.map { (x, y) -> y.first - x.first }
        assertThat(pulses.max()).isAtMost(StimulusSynth.PULSE_MS.toLong() + 1)
    }
}
