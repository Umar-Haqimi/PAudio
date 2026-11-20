package my.umarh.paudio.audio.effect

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import my.umarh.paudio.core.model.EffectCreationException
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EqConfig
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DynamicsProcessingBackendTest {
    private val worker = DynamicsProcessingBackend.newWorker()
    private val factory = FakePortFactory()
    private val ports get() = factory.ports
    private var dpAvailable = true
    private val backend = DynamicsProcessingBackend(factory, 0L, worker) { dpAvailable }

    private fun cfg(gain: Float = 1f, bands: Int = 6, preamp: Float = -1f) = EqConfig(
        preamp,
        List(bands) { (it + 1) * 100f },
        listOf(List(bands) { gain + it }, List(bands) { -gain - it }),
    )

    private fun flush() {
        worker.schedule({}, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        worker.shutdownNow()
    }

    @Test
    fun diffWritesOnlyChangedBands() {
        val h = backend.create(3, cfg())
        flush()
        val p = ports.single()
        val c = cfg()
        val c2 = c.copy(channelGainsDb = listOf(c.channelGainsDb[0].toMutableList().also { it[2] = 9f }, c.channelGainsDb[1]))
        h.apply(c2)
        flush()
        assertThat(p.bandWrites.map { it.first to it.second }).containsExactly(0 to 2)
        assertThat(p.inputGainWrites).isEmpty()
        h.apply(c2.copy(preampDb = -4f))
        flush()
        assertThat(p.inputGainWrites).containsExactly(0 to -4f, 1 to -4f)
        h.release()
        h.release() // idempotent
        assertThat(p.released).isTrue()
    }

    @Test
    fun rapidAppliesCoalesceToFinalState() {
        val h = backend.create(3, cfg())
        flush()
        val p = ports.single()
        repeat(20) { i -> h.apply(cfg(gain = 2f + i)) }
        flush()
        flush()
        assertThat(p.bands[0][0].gainDb).isEqualTo(21f)
    }

    @Test
    fun enableIsSynchronous() {
        val h = backend.create(3, cfg())
        h.setEnabled(true)
        assertThat(ports.single().isOn).isTrue()
        h.setEnabled(false)
        assertThat(ports.single().isOn).isFalse()
    }

    @Test
    fun createdEffectIsExplicitlyDisabledEvenIfEnabledFlagIsInherited() {
        factory.inherited = true
        backend.create(3, cfg())
        assertThat(ports.single().isOn).isFalse()
    }

    @Test
    fun readbackMismatchEmitsClampedEventWithBandCount() = runBlocking {
        val h = backend.create(3, cfg(gain = 6f))
        flush()
        h.clamped.test {
            factory.clampGainTo = 1f
            // a new port reads back clamped: rebuild through a band-count change
            h.apply(cfg(gain = 7f, bands = 4))
            flush()
            assertThat(awaitItem()).isEqualTo(4)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readbackMatchEmitsNothing() = runBlocking {
        val h = backend.create(3, cfg())
        h.clamped.test {
            flush()
            expectNoEvents()
        }
    }

    @Test
    fun repeatedClampsEmitRepeatedEvents() = runBlocking {
        factory.clampGainTo = 1f
        val h = backend.create(3, cfg(gain = 6f))
        h.clamped.test {
            h.apply(cfg(gain = 7f)) // same band count: write + readback again
            flush()
            assertThat(awaitItem()).isEqualTo(6)
            h.apply(cfg(gain = 8f))
            flush()
            assertThat(awaitItem()).isEqualTo(6)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setterUnsupportedMarksControlLost() {
        val h = backend.create(3, cfg())
        flush()
        ports.single().throwOnWrite = UnsupportedOperationException()
        ports.single().control = false
        h.apply(cfg(gain = 5f))
        flush()
        assertThat(h.control.value).isFalse()
    }

    @Test
    fun controlListenerDrivesFlowAndRewritesOnRegain() {
        val h = backend.create(3, cfg())
        flush()
        val p = ports.single()
        p.listener!!(false)
        assertThat(h.control.value).isFalse()
        p.bandWrites.clear()
        p.listener!!(true)
        flush()
        flush()
        assertThat(h.control.value).isTrue()
        assertThat(p.bandWrites).hasSize(12) // full rewrite, both channels
    }

    @Test
    fun regainedControlReassertsDesiredDisabledImmediately() {
        val h = backend.create(3, cfg())
        h.setEnabled(false)
        val p = ports.single()
        p.isOn = true // the other controller left it enabled; we inherit that on regain
        p.listener!!(true)
        assertThat(p.isOn).isFalse() // synchronously, before any write batch ran
    }

    @Test
    fun bandCountChangeRebuildsEffectAndKeepsDesiredState() {
        val h = backend.create(3, cfg(bands = 6))
        h.setEnabled(true)
        h.apply(cfg(bands = 4))
        flush()
        assertThat(ports).hasSize(2)
        assertThat(ports[0].released).isTrue()
        assertThat(ports[1].isOn).isTrue()
    }

    @Test
    fun rebuildWithInheritedEnabledStaysOffWhenDisabledDesired() {
        val h = backend.create(3, cfg(bands = 6))
        factory.inherited = true
        h.apply(cfg(bands = 4))
        flush()
        assertThat(ports).hasSize(2)
        assertThat(ports[1].isOn).isFalse()
    }

    @Test
    fun setEnabledDuringSlowRebuildDoesNotBlockAndWins() {
        val h = backend.create(3, cfg(bands = 6))
        h.setEnabled(true)
        factory.inherited = true
        val gate = CountDownLatch(1)
        factory.gate = gate
        h.apply(cfg(bands = 4))
        assertThat(factory.enteredGate.await(5, TimeUnit.SECONDS)).isTrue()

        val t = Thread { h.setEnabled(false) }
        t.start()
        t.join(2_000)
        assertThat(t.isAlive).isFalse() // waits at most REBUILD_WAIT_MS, never for the whole rebuild
        gate.countDown()
        awaitCondition { ports.size == 2 }
        flush()
        assertThat(ports[1].isOn).isFalse()
    }

    @Test
    fun failedRebuildExposesFailureKind() {
        val h = backend.create(3, cfg(bands = 6))
        factory.failures += RuntimeException("1")
        factory.failures += RuntimeException("2")
        h.apply(cfg(bands = 4))
        flush()
        assertThat(h.failed.value).isEqualTo(EffectFailureKind.CREATION_FAILED)
        assertThat(h.control.value).isFalse()
    }

    @Test
    fun unsupportedFromConstructorIsConflict() {
        factory.failures += UnsupportedOperationException()
        val e = assertThrows(EffectCreationException::class.java) { backend.create(3, cfg()) }
        assertThat(e.kind).isEqualTo(EffectFailureKind.CONFLICT)
    }

    @Test
    fun unsupportedWithoutPlatformDpIsCreationFailed() {
        dpAvailable = false
        factory.failures += UnsupportedOperationException()
        val e = assertThrows(EffectCreationException::class.java) { backend.create(3, cfg()) }
        assertThat(e.kind).isEqualTo(EffectFailureKind.CREATION_FAILED)
    }

    @Test
    fun runtimeFailureRetriesWithMbcThenSucceeds() {
        factory.failures += RuntimeException("boom")
        backend.create(3, cfg())
        assertThat(ports.single().mbc).isTrue()
    }

    @Test
    fun failureAfterRetryIsCreationFailed() {
        factory.failures += RuntimeException("1")
        factory.failures += IllegalArgumentException("2")
        val e = assertThrows(EffectCreationException::class.java) { backend.create(3, cfg()) }
        assertThat(e.kind).isEqualTo(EffectFailureKind.CREATION_FAILED)
    }

    @Test
    fun writeBatchesAreSpacedAtLeast50MsApartAtExecutionTime() {
        val w = DynamicsProcessingBackend.newWorker()
        try {
            val spaced = DynamicsProcessingBackend(factory, MIN_WRITE_SPACING_MS, w) { true }
            val h1 = spaced.create(1, cfg())
            val h2 = spaced.create(2, cfg())
            // queue several batches back to back from two handles
            h1.apply(cfg(gain = 2f))
            h2.apply(cfg(gain = 3f))
            h1.apply(cfg(gain = 4f))
            awaitCondition { ports[0].bands[0][0].gainDb == 4f && ports[1].bands[0][0].gainDb == 3f }
            val stamps = (ports[0].writeNanos + ports[1].writeNanos).sorted()
            // writes within one batch are back to back; find batch boundaries (gap > 10 ms) and check them
            val gaps = stamps.zipWithNext { a, b -> (b - a) / 1_000_000.0 }.filter { it > 10.0 }
            assertThat(gaps).isNotEmpty()
            assertThat(gaps.min()).isAtLeast(45.0)
        } finally {
            w.shutdownNow()
        }
    }

    @Test
    fun rebuildNeverEnablesFreshPortAfterDisableReturned() {
        val h = backend.create(3, cfg(bands = 6))
        h.setEnabled(true)
        factory.inherited = true
        val gate = CountDownLatch(1)
        factory.gate = gate
        h.apply(cfg(bands = 4)) // desired() == true while the new port is constructed
        assertThat(factory.enteredGate.await(5, TimeUnit.SECONDS)).isTrue()
        h.setEnabled(false)
        gate.countDown()
        awaitCondition { ports.size == 2 }
        flush()
        // the fresh port inherits true but we only ever write false to it
        assertThat(ports[1].isOn).isFalse()
        assertThat(ports[1].enabledWrites).doesNotContain(true)
    }

    @Test
    fun portIsDisabledBeforeEveryRelease() {
        val h = backend.create(3, cfg(bands = 6))
        h.setEnabled(true)
        h.apply(cfg(bands = 4)) // rebuild releases ports[0]
        flush()
        assertThat(ports[0].enabledAtRelease).isFalse()
        h.setEnabled(true)
        h.release()
        assertThat(ports[1].enabledAtRelease).isFalse()
    }

    @Test
    fun lateLostControlDoesNotOverwriteRegain() {
        // The write throws UOE, but the control listener reports the regain before the failure handler runs.
        val inners = ArrayList<FakePort>()
        var armed = false
        val f = DpPortFactory { _, _, c, mbc ->
            val inner = FakePort(c, mbc, false, null).also { inners += it }
            object : DpPort by inner {
                override fun setPreEqBand(channel: Int, index: Int, band: DpBand) {
                    if (armed) {
                        armed = false
                        inner.listener!!(true)
                        throw UnsupportedOperationException()
                    }
                    inner.setPreEqBand(channel, index, band)
                }
            }
        }
        val b = DynamicsProcessingBackend(f, 0L, worker) { true }
        val h = b.create(3, cfg())
        flush()
        armed = true
        h.apply(cfg(gain = 5f))
        flush()
        flush()
        assertThat(h.control.value).isTrue()
    }

    @Test
    fun failedRebuildThenWriteStaysDirty() {
        val h = backend.create(3, cfg(bands = 6))
        flush()
        factory.failures += RuntimeException("1")
        factory.failures += RuntimeException("2")
        h.apply(cfg(bands = 4))
        flush()
        assertThat(h.failed.value).isEqualTo(EffectFailureKind.CREATION_FAILED)
        // a later write for the old layout is skipped (port released) and must not be recorded as applied
        h.apply(cfg(bands = 6, gain = 9f))
        flush()
        assertThat(ports.single().bandWrites).isEmpty()
    }
}
