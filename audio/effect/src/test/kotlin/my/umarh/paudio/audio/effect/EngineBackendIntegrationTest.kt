package my.umarh.paudio.audio.effect

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real threads: the real [DynamicsProcessingBackend] (over fake ports) and/or a real engine thread. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EngineBackendIntegrationTest {
    private val worker = DynamicsProcessingBackend.newWorker()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val source = FakeSessionSource()

    @After
    fun tearDown() {
        engineScope.cancel()
        worker.shutdownNow()
    }

    private fun engine(backend: EffectBackend) =
        EffectEngine(backend, { source }, { false }, engineScope)

    private fun cfg(bands: Int, gain: Float = 6f) = EqConfig(
        -1f, List(bands) { (it + 1) * 100f }, listOf(List(bands) { gain }, List(bands) { gain }),
    )

    @Test
    fun p0_realBackendInheritedEnabledStaysOffDuringBypass_includingRebuild() {
        val factory = FakePortFactory().apply { inherited = true }
        val e = engine(DynamicsProcessingBackend(factory, 0L, worker) { true })
        e.setRoute(FakeOutputDevices.WIRED_HEADPHONES, cfg(6))
        e.setRunning(true)
        val token = e.acquire(BypassReason.CALIBRATION)
        awaitCondition { source.open(5, "p"); e.diagnostics.value.liveSessions == 1 }
        Thread.sleep(100)
        assertThat(factory.ports).isEmpty() // no creation during a bypass
        token.close()
        awaitCondition { factory.ports.size == 1 && factory.ports[0].isOn }
        val token2 = e.acquire(BypassReason.CALIBRATION)
        assertThat(factory.ports.filter { it.isOn && !it.released }).isEmpty()
        // ladder rebuild (new band count) while bypassed
        e.setTarget(cfg(4))
        awaitCondition { factory.ports.size == 2 }
        Thread.sleep(150)
        assertThat(factory.ports.filter { it.isOn && !it.released }).isEmpty()
        // retry() while bypassed
        e.retry()
        Thread.sleep(100)
        assertThat(factory.ports.size).isEqualTo(2) // retry's recreation waits for the token
        assertThat(factory.ports.filter { it.isOn && !it.released }).isEmpty()
        token2.close()
        awaitCondition { factory.ports.size == 3 && factory.ports.last().isOn }
    }

    @Test
    fun ladderWalksMainToFb51ToSpec40ThroughReadbackWithManySessions() {
        val factory = FakePortFactory().apply { clampGainTo = 1f }
        val e = engine(DynamicsProcessingBackend(factory, 0L, worker) { true })
        e.setRoute(FakeOutputDevices.WIRED_HEADPHONES, cfg(87))
        e.setRunning(true)
        // the engine thread subscribes asynchronously: re-OPEN (idempotent) until all three are live
        awaitCondition {
            for (i in 1..3) source.open(i, "p$i")
            e.diagnostics.value.liveSessions == 3
        }
        awaitCondition { e.diagnostics.value.ladder == DpLadderStep.FB51 }
        Thread.sleep(200)
        assertThat(e.diagnostics.value.ladder).isEqualTo(DpLadderStep.FB51) // three handles clamped: ONE step
        // the service re-plans for the FB51 layout; handles rebuild and read back clamped again
        e.setTarget(cfg(51))
        awaitCondition { e.diagnostics.value.ladder == DpLadderStep.SPEC40 }
        e.setTarget(cfg(40))
        Thread.sleep(300)
        assertThat(e.diagnostics.value.ladder).isEqualTo(DpLadderStep.SPEC40)
    }

    private class BlockingBackend(val inner: FakeEffectBackend) : EffectBackend {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)

        override fun create(session: Int, cfg: EqConfig): EffectHandle {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            return inner.create(session, cfg)
        }
    }

    @Test
    fun p0_acquireDuringSlowCreationDoesNotBlockAndHandleEndsUpOff() {
        val inner = FakeEffectBackend().apply { inheritedEnabled = true }
        val backend = BlockingBackend(inner)
        val e = engine(backend)
        e.setRoute(FakeOutputDevices.WIRED_HEADPHONES, cfg(6))
        e.setRunning(true)
        awaitCondition { source.open(5, "p"); backend.entered.count == 0L }

        // creation is in flight (binder call outside the engine lock): acquire must return promptly
        var token: AutoCloseable? = null
        val t = Thread { token = e.acquire(BypassReason.HEARING_TEST) }
        t.start()
        t.join(2_000)
        assertThat(t.isAlive).isFalse()

        backend.gate.countDown()
        awaitCondition { inner.handles.size == 1 && !inner.handles[0].enabled }
        Thread.sleep(150)
        assertThat(inner.enabledRequested).isEmpty()
        token!!.close()
        awaitCondition { inner.enabledRequested.size == 1 }
    }
}
