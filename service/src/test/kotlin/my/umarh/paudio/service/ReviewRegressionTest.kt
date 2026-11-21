package my.umarh.paudio.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.audio.effect.EffectEngine
import my.umarh.paudio.audio.effect.EngineMode
import my.umarh.paudio.audio.effect.LegacySessionSource
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.settings.EngineMode as StoredMode
import my.umarh.paudio.service.engine.ActiveProfileResolver
import my.umarh.paudio.service.engine.EngineController
import my.umarh.paudio.service.engine.ProfileCurveService
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRegressionTest {

    /** Same process: Turn off on BT, plug an unknown wired headset, Turn on. Legacy mode. */
    @Test
    fun restartInSameProcessNeverAppliesOldRoutesCurve() = runTest {
        val store = Store()
        store.seedStandard()
        store.settings.mode.value = StoredMode.LEGACY
        val fake = FakeEffectBackend()
        val enabledWith = java.util.concurrent.CopyOnWriteArrayList<my.umarh.paudio.core.model.EqConfig>()
        var recording = false
        val backend = object : my.umarh.paudio.core.model.EffectBackend {
            override fun create(session: Int, cfg: my.umarh.paudio.core.model.EqConfig): my.umarh.paudio.core.model.EffectHandle {
                val h = fake.create(session, cfg) as my.umarh.paudio.testing.FakeEffectHandle
                return object : my.umarh.paudio.core.model.EffectHandle by h {
                    override fun setEnabled(on: Boolean) { if (on && recording) enabledWith += h.config; h.setEnabled(on) }
                }
            }
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val engine = EffectEngine(
            backend,
            { mode -> if (mode == EngineMode.SESSION) FakeSessionSource() else LegacySessionSource() },
            { false }, scope, { testScheduler.currentTime },
        )
        val curves = ProfileCurveService(store.tests)
        val resolver = ActiveProfileResolver(store.devices, store.settings, store.profiles, scope)
        val controller = EngineController(
            engine, resolver, curves, store.settings, store.profiles, FakeLauncher(), store.flags,
            { 48_000 }, { true }, FakeSink(), ServiceFaults(), scope,
        )
        controller.onServiceStarted()
        val btCfg = curves.config(store.profiles.profile(1)!!, curves.loadSource(1), 48_000, DpLadderStep.MAIN)
        assertThat(fake.activeEnabled.single().config).isEqualTo(btCfg)

        controller.onServiceStopped()
        assertThat(fake.activeEnabled).isEmpty()
        val unknownWired = OutputDevice(OutputDeviceType.WIRED, "Other jack", "")
        store.devices.set(unknownWired) // no profile for it, nothing may be processed

        recording = true
        val before = fake.handles.size
        controller.onServiceStarted()
        val created = fake.handles.drop(before)
        // Expected: nothing created / enabled on the unknown wired route.
        assertThat(enabledWith).isEmpty()
        assertThat(created).isEmpty()
    }

    @Test
    fun realThreadsStopRightAfterStartLeavesEngineOff() = kotlinx.coroutines.runBlocking {
        val store = Store().also { it.seedStandard() }
        val backend = FakeEffectBackend()
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-engine") }
        val engineScope = CoroutineScope(exec.asCoroutineDispatcherX())
        val engine = EffectEngine(backend, { FakeSessionSource() }, { false }, engineScope)
        val ctlScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val resolver = ActiveProfileResolver(store.devices, store.settings, store.profiles, ctlScope)
        val controller = EngineController(
            engine, resolver, ProfileCurveService(store.tests), store.settings, store.profiles, FakeLauncher(), store.flags,
            { 48_000 }, { true }, FakeSink(), ServiceFaults(), ctlScope,
        )
        var leaked = 0
        repeat(System.getenv("PAUDIO_STRESS")?.toIntOrNull() ?: 3_000) {
            controller.onServiceStarted()
            // spin a little so the pipeline coroutine is mid-flight
            var x = 0; repeat(it % 200) { x += it }
            controller.onServiceStopped()
            Thread.sleep(0, 200_000)
            // drain the engine queue twice
            repeat(2) { exec.submit {}.get() }
            Thread.sleep(1)
            repeat(2) { exec.submit {}.get() }
            if (engine.state.value != my.umarh.paudio.audio.effect.EngineState.Off) {
                leaked++
                controller.onServiceStarted(); controller.onServiceStopped()
                repeat(2) { exec.submit {}.get() }
            }
        }
        println("LEAKED_RUNNING=$leaked")
        exec.shutdownNow()
        assertThat(leaked).isEqualTo(0)
    }


    @Test
    fun promptsOncePerHeadsetAndHonoursDontAsk() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        val a = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Car", "11:22")
        val b = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Other", "33:44")
        r.store.devices.set(a)
        r.controller.onServiceStarted()
        assertThat(r.sink.shown).hasSize(1)
        // Flapping and reconnecting do not re-prompt.
        r.store.devices.set(SPEAKER); r.store.devices.set(a); r.store.devices.set(SPEAKER); r.store.devices.set(a)
        assertThat(r.sink.shown).hasSize(1)
        // A service restart in the same process does not re-prompt either.
        r.controller.onServiceStopped(); r.controller.onServiceStarted()
        assertThat(r.sink.shown).hasSize(1)
        // A persisted "don't ask" suppresses a not-yet-prompted headset.
        r.store.flags.ignoredHeadsetKeys = setOf(b.headsetKey.value)
        r.store.devices.set(b)
        assertThat(r.sink.shown).hasSize(1)
    }

    @Test
    fun togglesAreBasedOnTheStoredValueAndUseTheSingleColumnUpdate() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.store.profiles.updateSettings(1, my.umarh.paudio.data.repo.ProfileSettings(0.9f, 1f, 2f, 3f, true))
        assertThat(r.controller.togglePersonalized()).isTrue()
        assertThat(r.controller.togglePersonalized()).isTrue()
        assertThat(r.controller.togglePersonalized()).isTrue()
        val p = r.store.profiles.profile(1)!!
        assertThat(p.personalizationOn).isFalse() // three flips from true
        assertThat(p.compStrength).isEqualTo(0.9f) // other settings untouched
        assertThat(r.store.profiles.setPersonalizationCalls).isEqualTo(3)
    }

    @Test
    fun stopThenStartWithAModeSwitchInBetweenAppliesTheNewMode() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        r.controller.onServiceStarted()
        r.controller.onServiceStopped()
        r.store.settings.mode.value = StoredMode.LEGACY
        r.controller.onServiceStarted()
        r.legacy.open(0, "android")
        assertThat(r.engine.state.value).isEqualTo(my.umarh.paudio.audio.effect.EngineState.Legacy(active = true))
    }

    @Test
    fun routeFailuresAreRetriedAndSurfacedNotFatal() = runTest {
        val r = Rig(this)
        r.store.seedStandard()
        var boom = true
        val flaky = object : my.umarh.paudio.data.repo.HearingTestRepository by r.store.tests {
            override fun observeForProfile(profileId: Long) =
                if (boom) kotlinx.coroutines.flow.flow<List<my.umarh.paudio.data.db.HearingTestEntity>> { boom = false; error("db") }
                else r.store.tests.observeForProfile(profileId)
        }
        val faults = ServiceFaults()
        val controller = EngineController(
            r.engine, r.resolver, ProfileCurveService(flaky), r.store.settings, r.store.profiles, r.launcher,
            r.store.flags, { 48_000 }, { true }, r.sink, faults, r.scope,
        )
        controller.onServiceStarted()
        assertThat(faults.active.value).isTrue()
        advanceTimeBy(1_500)
        assertThat(faults.active.value).isFalse() // retried and recovered
        r.session.open(5, "p")
        assertThat(r.backend.activeEnabled).hasSize(1)
    }

    private fun java.util.concurrent.ExecutorService.asCoroutineDispatcherX() =
        this.asCoroutineDispatcher()
}
