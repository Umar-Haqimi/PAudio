package my.umarh.paudio.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineMode
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.core.dsp.DpEqPlanner
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.settings.EngineMode as StoredMode
import my.umarh.paudio.service.engine.ActiveProfile
import my.umarh.paudio.service.engine.EngineController
import my.umarh.paudio.service.engine.ProfileCurveService
import my.umarh.paudio.service.engine.ActiveProfileResolver
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class EngineControllerTest {

    @Test
    fun deviceSwitchAppliesTheNewHeadsetsProfileThroughSetRoute() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")

        val btCfg = r.expectedConfig(1)
        val wiredCfg = r.expectedConfig(2)
        assertThat(btCfg).isNotEqualTo(wiredCfg)
        val handle = r.backend.handles.single()
        assertThat(handle.config).isEqualTo(btCfg)
        assertThat(handle.enabled).isTrue()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("a.player")))

        r.store.devices.set(WIRED)

        assertThat(r.backend.activeEnabled.map { it.config }).containsExactly(wiredCfg)
        // The BT curve was never re-applied to the wired route after the switch.
        val afterSwitch = r.backend.handles.flatMap { it.appliedConfigs }.dropWhile { it != wiredCfg }
        assertThat(afterSwitch.toSet()).containsExactly(wiredCfg)

        r.store.devices.set(BT)
        assertThat(r.backend.activeEnabled.map { it.config }).containsExactly(btCfg)
    }

    @Test
    fun speakerOutputIsNeverProcessed() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.store.devices.set(SPEAKER)
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")

        assertThat(r.engine.state.value).isEqualTo(EngineState.SpeakerOutput)
        assertThat(r.backend.createCalls).isEmpty()
        assertThat(r.backend.enabledRequested).isEmpty()

        // Switching from headphones to the speaker disables/releases what was there.
        r.store.devices.set(BT)
        assertThat(r.backend.activeEnabled).hasSize(1)
        r.store.devices.set(SPEAKER)
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.engine.state.value).isEqualTo(EngineState.SpeakerOutput)
    }

    @Test
    fun unknownHeadsetIsNoProfileAndPromptsOncePerSituation() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        val unknown = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Other Buds", "11:22")
        r.store.devices.set(unknown)
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")

        assertThat(r.engine.state.value).isEqualTo(EngineState.NoProfile(unknown))
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.sink.shown).containsExactly(unknown.headsetKey.value to 3L) // offers the "Any" profile

        // "Use Any": remembered by the DataStore map -> the Any profile's curve is applied.
        r.resolver.useProfileFor(unknown.headsetKey.value, 3)
        assertThat(r.engine.state.value).isEqualTo(EngineState.Active(listOf("a.player")))
        assertThat(r.backend.activeEnabled.single().config).isEqualTo(r.expectedConfig(3))
        assertThat(r.sink.cleared).isAtLeast(1)
    }

    @Test
    fun gapWhileTheNewProfileLoadsNeverRunsTheOldCurve() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")
        val btCfg = r.expectedConfig(1)
        assertThat(r.backend.activeEnabled.single().config).isEqualTo(btCfg)

        // The wired profile's test is still being read from the database.
        r.store.tests.gates[2] = CompletableDeferred()
        r.store.devices.set(WIRED)
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.engine.state.value).isEqualTo(EngineState.NoProfile(WIRED))
        assertThat(r.backend.handles.flatMap { it.appliedConfigs }.count { it == btCfg }).isEqualTo(1)

        r.store.tests.gates.getValue(2).complete(Unit)
        assertThat(r.backend.activeEnabled.single().config).isEqualTo(r.expectedConfig(2))
    }

    @Test
    fun personalizedToggleIsPersistedInTheProfileAndDisablesTheHandle() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")
        assertThat(r.backend.activeEnabled).hasSize(1)

        assertThat(r.controller.setPersonalized(false)).isTrue()

        assertThat(r.store.profiles.profile(1)!!.personalizationOn).isFalse()
        assertThat(r.controller.personalized.value).isFalse()
        assertThat(r.backend.enabledRequested).isEmpty()
        assertThat(r.backend.handles.single().released).isFalse() // handle kept, just disabled (fast toggle)

        assertThat(r.controller.togglePersonalized()).isTrue()
        assertThat(r.backend.activeEnabled).hasSize(1)

        r.store.devices.set(SPEAKER)
        assertThat(r.controller.setPersonalized(false)).isFalse() // nothing to toggle without a profile
    }

    @Test
    fun processDeathRestoreInLegacyOriginalMode() = runTest {
        val first = Rig(this)
        first.store.seedStandard()
        first.store.settings.mode.value = StoredMode.LEGACY
        first.store.profiles.updateSettings(1, my.umarh.paudio.data.repo.ProfileSettings(0.5f, 0f, 0f, 0f, false))
        first.controller.onServiceStarted()
        // The old process died while a test was running: its token dies with it.
        first.engine.acquire(BypassReason.HEARING_TEST)

        // "New process": fresh engine + controller over the same persisted state.
        val second = Rig(this, first.store)
        second.controller.onServiceStarted()
        assertThat(second.engine.bypassed.value).isFalse()
        second.legacy.open(0, "android")

        assertThat(second.engine.state.value).isEqualTo(EngineState.Legacy(active = false))
        val h = second.backend.handles.single()
        assertThat(h.session).isEqualTo(0)
        assertThat(h.config).isEqualTo(second.expectedConfig(1))
        assertThat(h.enabled).isFalse() // Original was persisted
        assertThat(second.backend.handles).hasSize(1)

        second.controller.setPersonalized(true)
        assertThat(second.engine.state.value).isEqualTo(EngineState.Legacy(active = true))
        assertThat(second.backend.activeEnabled).hasSize(1)
    }

    @Test
    fun processDeathRestoreInSessionModeNotBypassedAndEnabled() = runTest {
        val first = Rig(this)
        first.store.seedStandard()
        first.controller.onServiceStarted()
        first.session.open(7, "p")
        first.engine.acquire(BypassReason.RESULTS_AB) // never closed: process "dies"

        val second = Rig(this, first.store)
        second.controller.onServiceStarted()
        second.session.open(7, "p")

        assertThat(second.engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
        assertThat(second.backend.activeEnabled.single().config).isEqualTo(second.expectedConfig(1))
    }

    @Test
    fun ladderStepReplansWithTheNewBandCount() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")
        val handle = r.backend.handles.single()
        val mainBands = handle.config.bandCount
        assertThat(mainBands).isEqualTo(r.expectedConfig(1).bandCount)

        handle.emitClamped() // HAL clamped the MAIN layout

        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.FB51)
        val fb = r.expectedConfig(1, DpLadderStep.FB51)
        assertThat(fb.bandCount).isNotEqualTo(mainBands)
        assertThat(handle.config).isEqualTo(fb)
        assertThat(handle.enabled).isTrue()

        handle.emitClamped(fb.bandCount)
        assertThat(r.engine.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
        assertThat(handle.config).isEqualTo(r.expectedConfig(1, DpLadderStep.SPEC40))
    }

    @Test
    fun modeChangeIsPersistedAndAppliedAndMutuallyExclusive() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")
        assertThat(r.backend.handles).hasSize(1)

        r.controller.setMode(EngineMode.LEGACY)

        assertThat(r.store.settings.mode.value).isEqualTo(StoredMode.LEGACY)
        assertThat(r.backend.handles.single().released).isTrue() // session-mode handle released first
        assertThat(r.engine.state.value).isEqualTo(EngineState.Legacy(active = false))
    }

    @Test
    fun stoppingTheServiceStopsTheEngine() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.session.open(5, "a.player")
        assertThat(r.controller.running.value).isTrue()

        r.controller.onServiceStopped()

        assertThat(r.controller.running.value).isFalse()
        assertThat(r.engine.state.value).isEqualTo(EngineState.Off)
        assertThat(r.backend.enabledRequested).isEmpty()
    }

    @Test
    fun startAndStopTalkToTheLauncherAndPersistTheOffFlag() = runTest {
        val r = Rig(this)
        r.store.flags.userTurnedOff = true
        assertThat(r.controller.startIfAllowed()).isFalse() // the user turned it off: app start must not revive it
        assertThat(r.launcher.starts).isEqualTo(0)

        assertThat(r.controller.start()).isTrue()
        assertThat(r.store.flags.userTurnedOff).isFalse()
        assertThat(r.launcher.starts).isEqualTo(1)

        r.controller.stop()
        assertThat(r.store.flags.userTurnedOff).isTrue()
        assertThat(r.launcher.stops).isEqualTo(1)

        r.launcher.failWith = IllegalStateException("not allowed")
        assertThat(r.controller.start()).isFalse()

        r.store.settings.onboarding.value = false
        r.store.flags.userTurnedOff = false
        r.launcher.failWith = null
        assertThat(r.controller.startIfAllowed()).isFalse() // onboarding not done
    }

    /**
     * The real engine runs on its own single thread and the controller on another pool: hammer route changes and
     * check the end state and that no handle ever held a config of the wrong headset after settling.
     */
    @Test
    fun realThreadsRapidDeviceSwitchingSettlesOnTheRightCurve() = runBlocking {
        val store = Store().also { it.seedStandard() }
        val backend = FakeEffectBackend()
        val session = FakeSessionSource()
        val engineExec = Executors.newSingleThreadExecutor { Thread(it, "test-engine") }
        val engineScope = CoroutineScope(engineExec.asCoroutineDispatcher())
        val ctrlScope = CoroutineScope(Dispatchers.Default)
        try {
            val engine = EffectEngine(backend, { session }, { false }, engineScope)
            val curves = ProfileCurveService(store.tests)
            val resolver = ActiveProfileResolver(store.devices, store.settings, store.profiles, ctrlScope)
            val controller = EngineController(
                engine, resolver, curves, store.settings, store.profiles, FakeLauncher(), store.flags,
                { 48_000 }, { true }, FakeSink(), ctrlScope,
            )
            controller.onServiceStarted()
            val btCfg = curves.config(store.profiles.profile(1)!!, curves.loadSource(1), 48_000, DpLadderStep.MAIN)
            val wiredCfg = curves.config(store.profiles.profile(2)!!, curves.loadSource(2), 48_000, DpLadderStep.MAIN)

            withTimeout(10_000) {
                while (engine.state.value != EngineState.Waiting) delay(5)
                while (backend.handles.isEmpty()) {
                    session.open(9, "p")
                    delay(10)
                }
                repeat(100) { i ->
                    store.devices.set(if (i % 2 == 0) WIRED else BT)
                    if (i % 7 == 0) delay(3)
                }
                store.devices.set(WIRED)
                while (backend.activeEnabled.map { it.config } != listOf(wiredCfg)) delay(10)
            }
            // Every config that ever reached any handle belongs to one of the two profiles (no hybrid/stale mix).
            assertThat(setOf(btCfg, wiredCfg)).containsAtLeastElementsIn(backend.handles.flatMap { it.appliedConfigs }.toSet())
            assertThat(backend.activeEnabled.single().config).isEqualTo(wiredCfg)
            assertThat(engine.state.value).isEqualTo(EngineState.Active(listOf("p")))
            controller.onServiceStopped()
        } finally {
            ctrlScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            engineExec.shutdownNow()
        }
    }
}
