package my.umarh.paudio.audio.effect

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeSessionSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Scratch (review): real-thread interleavings around bypass. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReviewRaceTest {
    private val worker = DynamicsProcessingBackend.newWorker()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val source = FakeSessionSource()

    @After
    fun tearDown() {
        engineScope.cancel()
        worker.shutdownNow()
    }

    private fun cfg(bands: Int, gain: Float = 6f) = EqConfig(
        -1f, List(bands) { (it + 1) * 100f }, listOf(List(bands) { gain }, List(bands) { gain }),
    )

    /** Records every setEnabled(true) that happens after [bypassReturnedAt] was stamped. */
    private class Spy {
        @Volatile var bypassReturnedAt = Long.MAX_VALUE
        val enablesAfterBypass = CopyOnWriteArrayList<Int>()
    }

    /** Handle-level: rebuild reads desired()=true, then setEnabled(false) returns, then the fresh port is ENABLED. */
    @Test
    fun rebuild_createPortEnablesFreshPortAfterBypassReturned() {
        val spy = Spy()
        val enables = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        var n = 0
        val factory = DpPortFactory { _, _, c, mbc ->
            val idx = n++
            if (idx == 1) {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            val inner = FakePort(c, mbc, false, null)
            object : DpPort by inner {
                override fun setEnabled(on: Boolean) {
                    inner.setEnabled(on)
                    if (on && System.nanoTime() > spy.bypassReturnedAt) enables += "port$idx"
                }
            }
        }
        val backend = DynamicsProcessingBackend(factory, 0L, worker) { true }
        val h = backend.create(3, cfg(6))
        h.setEnabled(true) // personalised, playing
        h.apply(cfg(4)) // ladder step -> rebuild on worker; desired() read as true before construction
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        h.setEnabled(false) // what acquire() does; returns at once because rebuilding == true
        spy.bypassReturnedAt = System.nanoTime()
        gate.countDown()
        worker.schedule({}, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
        // Expect empty if P0 holds. Non-empty proves the fresh port was enabled while bypass was in force.
        assertThat(enables).isEmpty()
    }

    /** Engine-level: same window through the real engine thread + real worker. */
    @Test
    fun engine_acquireDuringRebuild_freshPortEnabledAfterAcquireReturned() {
        val spy = Spy()
        val enables = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        var n = 0
        val factory = DpPortFactory { _, _, c, mbc ->
            val idx = n++
            if (idx == 1) {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            val inner = FakePort(c, mbc, false, null)
            object : DpPort by inner {
                override fun setEnabled(on: Boolean) {
                    inner.setEnabled(on)
                    if (on && System.nanoTime() > spy.bypassReturnedAt) enables += "port$idx"
                }
            }
        }
        val e = EffectEngine(DynamicsProcessingBackend(factory, 0L, worker) { true }, { source }, { false }, engineScope)
        e.setRoute(FakeOutputDevices.WIRED_HEADPHONES, cfg(6))
        e.setRunning(true)
        awaitCondition { source.open(5, "p"); e.state.value is EngineState.Active }
        e.setTarget(cfg(4))
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val token = e.acquire(BypassReason.CALIBRATION)
        spy.bypassReturnedAt = System.nanoTime()
        gate.countDown()
        Thread.sleep(200)
        assertThat(enables).isEmpty()
        token.close()
    }

    /**
     * DP constructor inherits enabled=true; acquire() must not return before createPort's setEnabled(false) ran
     * (bounded wait for the in-flight creation).
     */
    @Test
    fun engine_acquireWaitsForInFlightCreation() {
        val disableGate = CountDownLatch(1)
        val constructed = CountDownLatch(1)
        val ports = CopyOnWriteArrayList<FakePort>()
        val factory = DpPortFactory { _, _, c, mbc ->
            val inner = FakePort(c, mbc, true, null).also { ports += it }
            constructed.countDown()
            object : DpPort by inner {
                override fun setEnabled(on: Boolean) {
                    if (!on) disableGate.await(10, TimeUnit.SECONDS)
                    inner.setEnabled(on)
                }
            }
        }
        val e = EffectEngine(DynamicsProcessingBackend(factory, 0L, worker) { true }, { source }, { false }, engineScope)
        e.setRoute(FakeOutputDevices.WIRED_HEADPHONES, cfg(6))
        e.setRunning(true)
        awaitCondition { source.open(5, "p"); constructed.count == 0L }
        // creation completes (disable applied) shortly after acquire() starts waiting
        val releaser = Thread {
            Thread.sleep(50)
            disableGate.countDown()
        }.also { it.start() }
        val token = e.acquire(BypassReason.CALIBRATION)
        assertThat(ports.single().isOn).isFalse() // P0: off by the time acquire() returned
        releaser.join()
        token.close()
    }
}
