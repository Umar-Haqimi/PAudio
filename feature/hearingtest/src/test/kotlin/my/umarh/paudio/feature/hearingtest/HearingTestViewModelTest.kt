package my.umarh.paudio.feature.hearingtest

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.EnvSnapshot
import my.umarh.paudio.core.model.NoiseMeter
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.testing.FakeEnvironmentChecks
import my.umarh.paudio.testing.FakeOutputDevices
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.reflect.KClass

/** Counts live collectors of the meter flow (the microphone is "open" while it is > 0). */
class CountingNoiseMeter : NoiseMeter {
    val active = AtomicInteger(0)
    val starts = AtomicInteger(0)
    val source = MutableSharedFlow<Float>(extraBufferCapacity = 64)

    override fun levels(): Flow<Float> = source
        .onStart { active.incrementAndGet(); starts.incrementAndGet() }
        .onCompletion { active.decrementAndGet() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HearingTestViewModelTest {
    @After fun tearDown() = Dispatchers.resetMain()

    private class Setup(val rig: Rig, val env: FakeEnvironmentChecks, val noise: CountingNoiseMeter, val vm: HearingTestViewModel)

    private fun TestScope.setup(env: EnvSnapshot = EnvSnapshot(false, 0f, false, false), timed: Boolean = false): Setup {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed)
        val e = FakeEnvironmentChecks(env)
        val n = CountingNoiseMeter()
        val vm = HearingTestViewModel(
            requestedProfileId = null,
            deps = rig.deps,
            env = e,
            noise = n,
            dispatchers = SessionDispatcherFactory { rig.sessionDispatcher },
            randomSource = { Random(2) },
        )
        return Setup(rig, e, n, vm)
    }

    private fun TestScope.collectState(vm: HearingTestViewModel): () -> HearingTestUiState {
        var last: HearingTestUiState? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { last = it } }
        return { last!! }
    }

    @Test
    fun modeChoiceThenPrepShowsBlockingWarnings() = runTest {
        val s = setup(EnvSnapshot(mono = true, balance = 0.5f, spatializerOn = true, dndOn = true))
        s.rig.devices.set(FakeOutputDevices.SPEAKER)
        val state = collectState(s.vm)
        runCurrent()
        assertThat(state()).isEqualTo(HearingTestUiState.ModeChoice(null))
        s.vm.chooseMode(TestMode.THOROUGH)
        runCurrent()
        val prep = state() as HearingTestUiState.Prep
        assertThat(prep.mode).isEqualTo(TestMode.THOROUGH)
        assertThat(prep.blockers).containsAtLeast(PrepBlocker.Mono, PrepBlocker.Balance, PrepBlocker.Spatializer, PrepBlocker.NoHeadphones)
        assertThat(prep.canStart).isFalse()
        assertThat(prep.dnd).isEqualTo(DndStatus.ON)
        s.vm.startTest()
        advanceTimeBy(100)
        runCurrent()
        assertThat(s.vm.currentSession).isNull() // blocked: no session, no bypass
        assertThat(s.rig.trace.acquired.get()).isEqualTo(0)
    }

    @Test
    fun dndAndUnknownChecksAreInformationalNotBlocking() = runTest {
        val s = setup(EnvSnapshot(mono = false, balance = 0f, spatializerOn = null, dndOn = false))
        val state = collectState(s.vm)
        s.vm.chooseMode(TestMode.QUICK)
        runCurrent()
        val prep = state() as HearingTestUiState.Prep
        assertThat(prep.dnd).isEqualTo(DndStatus.OFF)
        assertThat(prep.someChecksUnknown).isTrue()
        assertThat(prep.canStart).isTrue()
        s.vm.setAncOn(true)
        runCurrent()
        assertThat((state() as HearingTestUiState.Prep).ancOn).isTrue()
    }

    @Test
    fun noiseMeterIsOptInStopsWhenCollectionStopsAndIsHiddenWhenDenied() = runTest {
        val s = setup()
        s.vm.chooseMode(TestMode.QUICK)
        // before opt-in the microphone is never touched, even if somebody collects the level
        val job1 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.noiseLevel.collect { } }
        runCurrent()
        assertThat(s.noise.starts.get()).isEqualTo(0)
        s.vm.onNoisePermissionResult(true)
        runCurrent()
        assertThat(s.noise.active.get()).isEqualTo(1)
        s.noise.source.tryEmit(-70f)
        runCurrent()
        assertThat(s.vm.noiseLevel.value).isEqualTo(NoiseLevel.QUIET)
        // the page leaves composition: the collector goes away and the meter stops AT ONCE (stopTimeout = 0)
        job1.cancel()
        runCurrent()
        assertThat(s.noise.active.get()).isEqualTo(0)

        val s2 = setup()
        s2.vm.onNoisePermissionResult(false)
        val state = collectState(s2.vm)
        s2.vm.chooseMode(TestMode.QUICK)
        runCurrent()
        assertThat((state() as HearingTestUiState.Prep).noise).isEqualTo(NoiseMeterState.DENIED)
        assertThat(s2.noise.starts.get()).isEqualTo(0)
    }

    @Test
    fun fullFlowThroughTheViewModelEndsWithAnOpenResultsEvent() = runTest {
        val s = setup()
        val state = collectState(s.vm)
        val events = mutableListOf<HearingTestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.events.collect { events += it } }
        Brain(s.rig, normalListener(), sessionProvider = { s.vm.currentSession!! }).install()
        s.vm.chooseMode(TestMode.QUICK)
        runCurrent()
        s.vm.startTest()
        advanceUntilIdle()
        assertThat(state()).isInstanceOf(HearingTestUiState.Active::class.java)
        assertThat(events).hasSize(1)
        val id = (events.single() as HearingTestEvent.OpenResults).testId
        assertThat(id).isEqualTo(42L)
        assertThat(s.rig.store.saved.single().test.ancOn).isFalse()
        assertThat(s.rig.trace.openCount).isEqualTo(0)
        assertThat(s.rig.tone.violations).isEmpty()
        assertThat(s.rig.tone.isOpen).isFalse()
    }

    /** Environment checks that suspend until released, so the double tap lands while the first start is validating. */
    private class SlowEnv : my.umarh.paudio.core.model.EnvironmentChecks {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        override suspend fun snapshot(): EnvSnapshot {
            if (calls.incrementAndGet() > 1) release.await() // the first call feeds the Prep page
            return EnvSnapshot(false, 0f, false, false)
        }
    }

    @Test
    fun doubleTapOnStartStartsOneSessionWithOneBypassToken() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig.virtual(testScheduler, TestMode.QUICK, timed = true)
        val env = SlowEnv()
        val vm = HearingTestViewModel(null, rig.deps, env, CountingNoiseMeter(), SessionDispatcherFactory { rig.sessionDispatcher }, { Random(2) })
        val state = collectState(vm)
        val brain = Brain(rig, normalListener(), scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + StandardTestDispatcher(testScheduler)), sessionProvider = { vm.currentSession!! })
        brain.autoQuick = false
        brain.install()
        vm.chooseMode(TestMode.QUICK)
        runCurrent()
        assertThat((state() as HearingTestUiState.Prep).canStart).isTrue()
        vm.startTest()
        vm.startTest() // second tap while the first is still suspended in the environment check
        runCurrent()
        val prep = state() as HearingTestUiState.Prep
        assertThat(prep.starting).isTrue()
        assertThat(prep.canStart).isFalse() // Start is disabled while starting
        vm.startTest() // a third tap, after recomposition would have disabled the button anyway
        env.release.complete(Unit)
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        val session = vm.currentSession!!
        assertThat(state()).isInstanceOf(HearingTestUiState.Active::class.java)
        assertThat(rig.trace.acquired.get()).isEqualTo(1)
        assertThat(rig.trace.openCount).isEqualTo(1)
        assertThat(rig.tone.openCount.get()).isEqualTo(1)
        session.cancelAndJoin()
        advanceUntilIdle()
        assertThat(rig.trace.openCount).isEqualTo(0)
        assertThat(vm.currentSession).isSameInstanceAs(session)
    }

    @Test
    fun tryAgainStartsTheNewSessionOnlyAfterTheOldOnesCleanup() = runTest {
        val s = setup(timed = true)
        val state = collectState(s.vm)
        val brain = Brain(s.rig, normalListener(), sessionProvider = { s.vm.currentSession!! }, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + StandardTestDispatcher(testScheduler)))
        brain.autoQuick = false
        brain.install()
        s.vm.chooseMode(TestMode.QUICK)
        runCurrent()
        s.vm.startTest()
        advanceTimeBy(20_000)
        runCurrent()
        val old = s.vm.currentSession!!
        s.vm.tryAgain() // cancels the old session; its cleanup runs on the session thread
        runCurrent()
        s.vm.startTest()
        advanceTimeBy(20_000)
        runCurrent()
        val fresh = s.vm.currentSession!!
        assertThat(fresh).isNotSameInstanceAs(old)
        assertThat(old.state.value).isEqualTo(SessionState.Aborted(AbortReason.USER))
        assertThat(state()).isInstanceOf(HearingTestUiState.Active::class.java)
        // tokens never overlapped: the old one was released before the new one was taken
        val events = s.rig.log.events
        val firstRelease = events.indexOf("bypass.release")
        val secondAcquire = events.withIndex().filter { it.value == "bypass.acquire" }.map { it.index }[1]
        assertThat(firstRelease).isLessThan(secondAcquire)
        assertThat(s.rig.trace.openCount).isEqualTo(1)
        fresh.cancelAndJoin()
        advanceUntilIdle()
        assertThat(s.rig.trace.openCount).isEqualTo(0)
    }

    @Test
    fun clearingTheViewModelCancelsTheSessionAndCleansUp() = runTest {
        val s = setup(timed = true)
        val brain = Brain(s.rig, normalListener(), sessionProvider = { s.vm.currentSession!! }, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + StandardTestDispatcher(testScheduler)))
        brain.autoQuick = false
        brain.install()
        s.vm.chooseMode(TestMode.QUICK)
        runCurrent()
        s.vm.startTest()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(s.rig.trace.openCount).isEqualTo(1) // mid-test: token held
        // ViewModelStore.clear() calls onCleared(), exactly like leaving the screen / process teardown
        val store = ViewModelStore()
        ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = s.vm as T
            },
        )[HearingTestViewModel::class.java]
        store.clear()
        advanceUntilIdle()
        assertThat(s.rig.trace.openCount).isEqualTo(0)
        assertThat(s.rig.tone.isOpen).isFalse()
        assertThat(s.rig.volume.index()).isEqualTo(s.rig.originalVolume)
        assertThat(s.rig.log.indexOf("tone.close")).isLessThan(s.rig.log.indexOf("bypass.release"))
    }
}
