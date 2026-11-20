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
        var sourceFails = false
        val scope: CoroutineScope =
            CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler))
        val engine = EffectEngine(
            backend,
            { mode ->
                if (sourceFails) error("no receiver")
                if (mode == EngineMode.SESSION) session else legacy
            },
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
    fun sixteenSessionCapEvictsLeastRecentlyOpened() = runTest {
        val r = Rig(this)
        r.start()
        for (i in 1..16) r.session.open(i, "p$i")
        assertThat(r.backend.handles.filter { !it.released }).hasSize(16)
        advanceTimeBy(1_000)
        r.session.open(1, "p1") // refresh 1: session 2 is now the least recently opened
        r.session.open(17, "p17")
        assertThat(r.engine.diagnostics.value.liveSessions).isEqualTo(16)
        assertThat(r.engine.diagnostics.value.sessionsEvicted).isEqualTo(1)
        assertThat(r.backend.handles.single { it.session == 2 }.released).isTrue()
        assertThat(r.backend.handles.single { it.session == 1 }.released).isFalse()
        // creation is rate limited (burst 16 per window): the 17th handle appears after the window
        assertThat(r.backend.handles.any { it.session == 17 }).isFalse()
        advanceTimeBy(EffectEngine.CREATE_WINDOW_MS)
        assertThat(r.backend.handles.single { it.session == 17 }.released).isFalse()
        assertThat(r.backend.handles.filter { !it.released }).hasSize(16)
    }

    @Test
    fun closeWithDifferentPackageIsIgnored() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "real.player")
        r.session.close(5, "spoof")
        assertThat(r.backend.handles.single().released).isFalse()
        r.session.close(5, "real.player")
        assertThat(r.backend.handles.single().released).isTrue()
    }

    @Test
    fun activeAppsAreDistinct() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "a")
        r.session.open(6, "a")
        r.session.open(7, "b")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("a", "b")))
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
        assertThat(r.backend.handles).hasSize(1) // no creation starts during a bypass
        assertThat(r.backend.enabledRequested).isEmpty()
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
    fun ladderStepsOncePerBandCountEvenWithManyClampedHandles() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.open(6, "q")
        r.session.open(7, "x")
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.MAIN)
        r.backend.handles.forEach { it.emitClamped() }
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.FB51) // not SPEC40
        // service re-plans with a different layout; clamps again on the new layout
        val fb = EqConfig.flat(bandCount = 20)
        r.engine.setTarget(fb)
        r.backend.handles.forEach { it.emitClamped(20) }
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
        assertThat(r.engine.diagnostics.value.reducedPrecision).isTrue()
        r.backend.handles.forEach { it.emitClamped(20) }
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
    }

    @Test
    fun staleClampEventForOldLayoutIsIgnored() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.engine.setTarget(EqConfig.flat(bandCount = 20))
        r.backend.handles.single().emitClamped(40) // old layout
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.MAIN)
    }

    @Test
    fun p0_inheritedEnabledFlagNeverProcessesDuringBypass() = runTest {
        val r = Rig(this)
        r.backend.inheritedEnabled = true // AOSP: new controller inherits the previous controller's enabled flag
        r.start()
        val t = r.engine.acquire(BypassReason.CALIBRATION)
        r.session.open(5, "p")
        assertThat(r.backend.handles).isEmpty() // no creation during a bypass
        r.engine.retry()
        assertThat(r.backend.enabledRequested).isEmpty()
        t.close()
        assertThat(r.backend.enabledRequested).hasSize(1)
    }

    @Test
    fun creationFailedClearedBySetModeSoLegacyCanBeTried() = runTest {
        val r = Rig(this)
        r.backend.failCreates = EffectFailureKind.CREATION_FAILED
        r.start()
        r.session.open(5, "p")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Error(EngineError.CREATION_FAILED))
        r.backend.failCreates = null
        r.engine.setMode(EngineMode.LEGACY)
        r.legacy.open(0, LEGACY_PACKAGE)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Legacy(active = true))
    }

    @Test
    fun creationFailedClearedByDeviceChange() = runTest {
        val r = Rig(this)
        r.backend.failCreates = EffectFailureKind.CREATION_FAILED
        r.start()
        r.session.open(5, "p")
        r.backend.failCreates = null
        r.engine.setRoute(FakeOutputDevices.BLUETOOTH_BUDS, r.cfg2)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }

    @Test
    fun handleFailureKindsMapToErrorAndConflict() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val h = r.backend.handles.single()
        h.setFailed(EffectFailureKind.CONFLICT)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Conflict)
        assertThat(h.released).isTrue() // zombie dropped; recreated on retry
        r.engine.retry()
        val h2 = r.backend.handles.last()
        assertThat(h2).isNotSameInstanceAs(h)
        h2.setFailed(EffectFailureKind.CREATION_FAILED)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Error(EngineError.CREATION_FAILED))
    }

    @Test
    fun failedRebuildDropsZombieAndRecreatesAfterDeviceChange() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        val h = r.backend.handles.single()
        h.setFailed(EffectFailureKind.CREATION_FAILED) // what a failed ladder rebuild reports
        assertThat(h.released).isTrue()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Error(EngineError.CREATION_FAILED))
        r.engine.setRoute(FakeOutputDevices.BLUETOOTH_BUDS, r.cfg2)
        assertThat(r.backend.handles).hasSize(2)
        val h2 = r.backend.handles.last()
        assertThat(h2.released).isFalse()
        assertThat(h2.enabled).isTrue()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }

    @Test
    fun releasingEnabledHandleIsObservableAndNextHandleIsRewritten() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.session.close(5, "p")
        // the fake models AOSP: the departing controller's enabled flag moves to the next controller
        r.session.open(6, "p")
        val next = r.backend.handles.last()
        assertThat(next.session).isEqualTo(6)
        assertThat(next.enabled).isTrue() // desired state, rewritten after inheriting
        val tok = r.engine.acquire(BypassReason.CALIBRATION)
        assertThat(r.backend.enabledRequested).isEmpty()
        tok.close()
    }

    @Test
    fun retryRestartsFailedSource() = runTest {
        val r = Rig(this)
        r.sourceFails = true
        r.start()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Error(EngineError.SOURCE_FAILED))
        r.sourceFails = false
        r.engine.retry()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Waiting)
        r.session.open(5, "p")
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }

    @Test
    fun setRouteIsAtomic() = runTest {
        val r = Rig(this)
        r.start()
        r.session.open(5, "p")
        r.engine.setRoute(FakeOutputDevices.BLUETOOTH_BUDS, r.cfg2)
        // the old curve was never applied to the new route and vice versa: one config change only
        assertThat(r.backend.handles.single().appliedConfigs).containsExactly(r.cfg, r.cfg2).inOrder()
    }

    @Test
    fun noSessionHintSilentDuringBypassAndWithoutProfile() = runTest {
        val r = Rig(this)
        r.start()
        r.music = true
        val t = r.engine.acquire(BypassReason.HEARING_TEST)
        advanceTimeBy(120_000)
        assertThat(r.engine.noSessionHint.value).isFalse()
        t.close()
        r.engine.setTarget(null)
        advanceTimeBy(120_000)
        assertThat(r.engine.noSessionHint.value).isFalse()
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

    @Test
    fun noCreationDuringBypassUntilTokenCloses() = runTest {
        val r = Rig(this)
        r.start()
        val tok = r.engine.acquire(BypassReason.CALIBRATION)
        r.session.open(5, "p")
        assertThat(r.backend.createCalls).isEmpty()
        tok.close()
        assertThat(r.backend.createCalls).containsExactly(5)
        assertThat(r.backend.handles.single().enabled).isTrue()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
    }
}
