package my.umarh.paudio.audio.effect

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.EffectCreationException
import my.umarh.paudio.core.model.EffectFailureKind
import my.umarh.paudio.core.model.EqConfig
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

private class FakePort(val cfg: EqConfig, val mbc: Boolean) : DpPort {
    val bands = Array(2) { ch -> Array(cfg.bandCount) { k -> DpBand(cfg.bandCutoffsHz[k], cfg.channelGainsDb[ch][k]) } }
    val bandWrites = mutableListOf<Triple<Int, Int, DpBand>>()
    val inputGainWrites = mutableListOf<Pair<Int, Float>>()
    @Volatile var isOn = false
    @Volatile var control = true
    @Volatile var released = false
    @Volatile var listener: ((Boolean) -> Unit)? = null
    @Volatile var clampGainTo: Float? = null
    @Volatile var throwOnWrite: RuntimeException? = null

    override fun setEnabled(on: Boolean) {
        if (!control) throw UnsupportedOperationException()
        isOn = on
    }

    override fun hasControl() = control

    override fun setInputGain(channel: Int, db: Float) {
        throwOnWrite?.let { throw it }
        inputGainWrites += channel to db
    }

    override fun setPreEqBand(channel: Int, index: Int, band: DpBand) {
        throwOnWrite?.let { throw it }
        bandWrites += Triple(channel, index, band)
        bands[channel][index] = band
    }

    override fun getPreEqBand(channel: Int, index: Int): DpBand {
        val b = bands[channel][index]
        return clampGainTo?.let { b.copy(gainDb = minOf(b.gainDb, it)) } ?: b
    }

    override fun setLimiterEnabled(channel: Int, enabled: Boolean) {}
    override fun setControlListener(listener: (Boolean) -> Unit) {
        this.listener = listener
    }

    override fun release() {
        released = true
    }
}

@RunWith(RobolectricTestRunner::class)
class DynamicsProcessingBackendTest {
    private val worker = DynamicsProcessingBackend.newWorker()
    private val ports = mutableListOf<FakePort>()
    private var failures = mutableListOf<RuntimeException>()
    private val factory = DpPortFactory { _, _, cfg, mbc ->
        if (failures.isNotEmpty()) throw failures.removeAt(0)
        FakePort(cfg, mbc).also { ports += it }
    }
    private val backend = DynamicsProcessingBackend(factory, 0L, worker)

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
    fun readbackMismatchSetsClamped() {
        val h = backend.create(3, cfg(gain = 6f))
        flush()
        ports.single().clampGainTo = 1f
        h.apply(cfg(gain = 7f))
        flush()
        flush()
        assertThat(h.clamped.value).isTrue()
    }

    @Test
    fun readbackMatchLeavesNotClamped() {
        val h = backend.create(3, cfg())
        flush()
        assertThat(h.clamped.value).isFalse()
    }

    @Test
    fun setterUnsupportedMarksControlLost() {
        val h = backend.create(3, cfg())
        flush()
        ports.single().throwOnWrite = UnsupportedOperationException()
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
    fun bandCountChangeRebuildsEffect() {
        val h = backend.create(3, cfg(bands = 6))
        h.setEnabled(true)
        h.apply(cfg(bands = 4))
        flush()
        assertThat(ports).hasSize(2)
        assertThat(ports[0].released).isTrue()
        assertThat(ports[1].isOn).isTrue()
    }

    @Test
    fun unsupportedFromConstructorIsConflict() {
        failures += UnsupportedOperationException()
        val e = assertThrows(EffectCreationException::class.java) { backend.create(3, cfg()) }
        assertThat(e.kind).isEqualTo(EffectFailureKind.CONFLICT)
    }

    @Test
    fun runtimeFailureRetriesWithMbcThenSucceeds() {
        failures += RuntimeException("boom")
        backend.create(3, cfg())
        assertThat(ports.single().mbc).isTrue()
    }

    @Test
    fun failureAfterRetryIsCreationFailed() {
        failures += RuntimeException("1")
        failures += IllegalArgumentException("2")
        val e = assertThrows(EffectCreationException::class.java) { backend.create(3, cfg()) }
        assertThat(e.kind).isEqualTo(EffectFailureKind.CREATION_FAILED)
    }
}
