package my.umarh.paudio.audio.effect

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EffectEngineTest {

    private class Rig(testScope: TestScope) {
        val backend = FakeEffectBackend()
        val session = FakeSessionSource()
        val legacy = FakeSessionSource()
        var music = false
        val scope: CoroutineScope =
            CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))
        val engine = EffectEngine(
            backend,
            { mode -> if (mode == EngineMode.SESSION) session else legacy },
            { music },
            scope,
            { testScope.testScheduler.currentTime },
        )
        val cfg = EqConfig.flat()
        val cfg2 = EqConfig.flat().copy(preampDb = -3f)

        fun start(device: OutputDevice? = FakeOutputDevices.WIRED_HEADPHONES, target: EqConfig? = cfg) {
            engine.setOutputDevice(device)
            engine.setTarget(target)
            engine.setRunning(true)
        }
    }

    @Test
    fun openCloseLifecycle() = runTest {
        val r = Rig(this)
        r.start()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Waiting)
        r.session.open(5, "a.player")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("a.player")))
        assertThat(r.backend.handles).hasSize(1)
        assertThat(r.backend.handles[0].session).isEqualTo(5)
        assertThat(r.backend.enabledRequested).hasSize(1)
        r.session.close(5, "a.player")
        assertThat(r.backend.handles[0].released).isTrue()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Waiting)
        assertThat(r.engine.diagnostics.value.liveSessions).isEqualTo(0)
    }

    @Test
    fun personalizedFalseKeepsHandlesDisabled() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        assertThat(r.backend.handles.single().enabled).isTrue()
        r.engine.setPersonalized(false)
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.backend.handles).hasSize(1) // kept
        r.engine.setPersonalized(true)
        assertThat(r.backend.enabledRequested).hasSize(1)
    }

    @Test
    fun invalidSessionsIgnoredByEngine() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(0, "p")
        r.session.open(-3, "p")
        assertThat(r.backend.handles).isEmpty()
    }

    @Test
    fun sixteenSessionCap() = runTest {
        val r = Rig(this)
        r.start()
        for (i in 1..20) r.session.open(i, "p$i")
        assertThat(r.backend.handles).hasSize(16)
        assertThat(r.engine.diagnostics.value.liveSessions).isEqualTo(16)
        assertThat(r.engine.diagnostics.value.sessionsIgnoredOverCap).isEqualTo(4)
        r.session.close(1, "p1")
        r.session.open(21, "p21")
        assertThat(r.engine.diagnostics.value.liveSessions).isEqualTo(16)
    }

    @Test
    fun reOpenDoesNotDuplicateHandle() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.open(5, "p")
        assertThat(r.backend.handles).hasSize(1)
    }

    @Test
    fun legacyAndSessionAreMutuallyExclusive() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.open(6, "q")
        assertThat(r.backend.handles.filter { !it.released }).hasSize(2)

        r.engine.setMode(EngineMode.LEGACY)
        // old session handles released BEFORE any legacy handle exists
        assertThat(r.backend.handles.filter { !it.released }).isEmpty()
        r.legacy.open(0, LEGACY_PACKAGE)
        assertThat(r.backend.handles.filter { !it.released }.map { it.session }).containsExactly(0)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Legacy(active = true))
        // session broadcasts while in legacy mode are not collected
        r.session.open(7, "x")
        assertThat(r.backend.handles.filter { !it.released }.map { it.session }).containsExactly(0)

        r.engine.setMode(EngineMode.SESSION)
        assertThat(r.backend.handles.filter { !it.released }).isEmpty()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Waiting)
    }

    @Test
    fun p0_noEnabledEffectWhileBypassTokenOpen() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        assertThat(r.backend.enabledRequested).hasSize(1)

        val t1 = r.engine.acquire(BypassReason.CALIBRATION)
        // synchronous: off the moment acquire returns
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Bypassed)
        assertThat(r.engine.bypassed.value).isTrue()

        // everything that could re-enable while bypassed
        r.session.open(6, "late")
        assertThat(r.backend.handles).hasSize(2) // created
        assertThat(r.backend.enabledRequested).isEmpty() // but kept disabled
        r.engine.setTarget(r.cfg2)
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.setOutputDevice(FakeOutputDevices.BLUETOOTH_BUDS)
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.setPersonalized(false)
        r.engine.setPersonalized(true)
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.retry()
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.setMode(EngineMode.LEGACY)
        r.legacy.open(0, LEGACY_PACKAGE)
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.setMode(EngineMode.SESSION)
        r.session.open(9, "again")
        assertThat(r.backend.enabledRequested).isEmpty()
        r.engine.setRunning(false)
        r.engine.setRunning(true)
        r.session.open(10, "restarted")
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Bypassed)

        t1.close()
        assertThat(r.backend.enabledRequested.map { it.session }).containsExactly(10)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("restarted")))
        assertThat(r.engine.bypassed.value).isFalse()
    }

    @Test
    fun p0_controlRegainedDuringBypassStaysOff() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val h = r.backend.handles.single()
        val t = r.engine.acquire(BypassReason.PREFERENCE_AB)
        h.setControl(false)
        h.setControl(true)
        assertThat(r.backend.enabledRequested).isEmpty()
        t.close()
        assertThat(r.backend.enabledRequested).hasSize(1)
    }

    @Test
    fun p0_nestedTokensAndIdempotentClose() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val a = r.engine.acquire(BypassReason.HEARING_TEST)
        val b = r.engine.acquire(BypassReason.RESULTS_AB)
        a.close()
        a.close() // idempotent: must not drop b's hold
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.engine.bypassed.value).isTrue()
        b.close()
        assertThat(r.backend.enabledRequested).hasSize(1)
        assertThat(r.engine.bypassed.value).isFalse()
    }

    @Test
    fun originalModeStaysOffAfterBypass() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.engine.setPersonalized(false)
        r.engine.acquire(BypassReason.OTHER).close()
        assertThat(r.backend.enabledRequested).isEmpty()
    }

    @Test
    fun restoreAfterSimulatedProcessDeath() = runTest {
        val r1 = Rig(this)
        r1.start()
        r1.session.open(5, "p")
        r1.engine.acquire(BypassReason.CALIBRATION) // never closed: process dies
        assertThat(r1.backend.enabledRequested).isEmpty()

        val r2 = Rig(this) // new process: fresh engine
        r2.start()
        assertThat(r2.engine.bypassed.value).isFalse()
        r2.session.open(5, "p")
        assertThat(r2.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
        assertThat(r2.backend.enabledRequested).hasSize(1)
        assertThat(r2.backend.handles.single().config).isEqualTo(r2.cfg)
    }

    @Test
    fun conflictOnControlLossAndAutoRecovery() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val h = r.backend.handles.single()
        h.setControl(false)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Conflict)
        h.setControl(true)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
        assertThat(h.enabled).isTrue()
        // config re-applied on regain
        assertThat(h.appliedConfigs.size).isAtLeast(2)
    }

    @Test
    fun conflictManualRetryRecreatesHandles() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val first = r.backend.handles.single()
        first.setControl(false)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Conflict)
        r.engine.retry()
        assertThat(first.released).isTrue()
        assertThat(r.backend.handles).hasSize(2)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
        assertThat(r.backend.enabledRequested).hasSize(1)
    }

    @Test
    fun creationConflictThenRetry() = runTest {
        val r = Rig(this)
        r.backend.failCreates = EffectFailureKind.CONFLICT
        r.start()
        r.session.open(5, "p")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Conflict)
        r.backend.failCreates = null
        r.engine.retry()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }

    @Test
    fun creationFailureIsErrorState() = runTest {
        val r = Rig(this)
        r.backend.failCreates = EffectFailureKind.CREATION_FAILED
        r.start()
        r.session.open(5, "p")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Error(EngineError.CREATION_FAILED))
        assertThat(r.backend.enabledRequested).isEmpty()
        r.backend.failCreates = null
        r.engine.retry()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }

    @Test
    fun deviceSwitchAppliesNewConfigToAllHandles() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.open(6, "q")
        r.engine.setTarget(r.cfg2)
        assertThat(r.backend.handles.map { it.config }).containsExactly(r.cfg2, r.cfg2)
        r.engine.setTarget(null)
        assertThat(r.engine.state.value).isEqualTo(EngineState.NoProfile(FakeOutputDevices.WIRED_HEADPHONES))
        assertThat(r.backend.enabledRequested).isEmpty()
    }

    @Test
    fun speakerOutputProcessesNothingAndCreatesNothing() = runTest {
        val r = Rig(this)
        r.start(device = FakeOutputDevices.SPEAKER)
        r.session.open(5, "p")
        assertThat(r.engine.state.value).isEqualTo(EngineState.SpeakerOutput)
        assertThat(r.backend.handles).isEmpty()
        r.engine.setOutputDevice(FakeOutputDevices.WIRED_HEADPHONES)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
        assertThat(r.backend.enabledRequested).hasSize(1)
        r.engine.setOutputDevice(null)
        assertThat(r.engine.state.value).isEqualTo(EngineState.SpeakerOutput)
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.backend.handles.single().released).isTrue()
    }

    @Test
    fun offReleasesEverything() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.engine.setRunning(false)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Off)
        assertThat(r.backend.handles.single().released).isTrue()
    }

    @Test
    fun ladderFallbackOnReadbackMismatch() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val h = r.backend.handles.single()
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.MAIN)
        h.setClamped(true)
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.FB51)
        h.setClamped(false)
        h.setClamped(true)
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
        assertThat(r.engine.diagnostics.value.reducedPrecision).isTrue()
        h.setClamped(false)
        h.setClamped(true)
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
    }

    @Test
    fun idleSessionsReleasedAfterTenMinutesWithoutMusic() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.open(6, "q")
        advanceTimeBy(EffectEngine.IDLE_MS - 60_000)
        r.session.open(6, "q") // re-OPEN refreshes
        advanceTimeBy(30_000)
        assertThat(r.backend.handles.filter { !it.released }).hasSize(2) // not yet idle
        advanceTimeBy(60_000)
        assertThat(r.backend.handles.single { it.session == 5 }.released).isTrue()
        assertThat(r.backend.handles.single { it.session == 6 }.released).isFalse()
        assertThat(r.engine.diagnostics.value.sessionsReleasedIdle).isEqualTo(1)
    }

    @Test
    fun activeMusicKeepsSessionsAlive() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.music = true
        advanceTimeBy(EffectEngine.IDLE_MS * 3)
        assertThat(r.backend.handles.single().released).isFalse()
    }

    @Test
    fun noSessionHintAfterSixtySecondsOfMusic() = runTest {
        val r = Rig(this)
        r.start()
        advanceTimeBy(120_000)
        assertThat(r.engine.noSessionHint.value).isFalse() // no music
        r.music = true
        advanceTimeBy(30_000)
        assertThat(r.engine.noSessionHint.value).isFalse()
        advanceTimeBy(40_000)
        assertThat(r.engine.noSessionHint.value).isTrue()
        r.session.open(5, "p")
        advanceTimeBy(10_000)
        assertThat(r.engine.noSessionHint.value).isFalse()
    }
}
