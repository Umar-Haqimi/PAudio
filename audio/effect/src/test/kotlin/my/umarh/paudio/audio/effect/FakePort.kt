package my.umarh.paudio.audio.effect

import my.umarh.paudio.core.model.EqConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Simulated platform effect; [inherited] mimics AOSP handing a new controller the previous enabled flag. */
internal class FakePort(val cfg: EqConfig, val mbc: Boolean, inherited: Boolean, val clampGainTo: Float?) : DpPort {
    val bands = Array(2) { ch -> Array(cfg.bandCount) { k -> DpBand(cfg.bandCutoffsHz[k], cfg.channelGainsDb[ch][k]) } }
    val bandWrites = CopyOnWriteArrayList<Triple<Int, Int, DpBand>>()
    val writeNanos = CopyOnWriteArrayList<Long>()
    val inputGainWrites = CopyOnWriteArrayList<Pair<Int, Float>>()
    @Volatile var isOn = inherited
    @Volatile var control = true
    @Volatile var released = false
    @Volatile var listener: ((Boolean) -> Unit)? = null
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
        writeNanos += System.nanoTime()
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

internal class FakePortFactory : DpPortFactory {
    val ports = CopyOnWriteArrayList<FakePort>()
    val failures = CopyOnWriteArrayList<RuntimeException>()
    @Volatile var inherited = false
    @Volatile var clampGainTo: Float? = null

    /** When set, creation blocks until released (signals [enteredGate] first). */
    @Volatile var gate: CountDownLatch? = null
    val enteredGate = CountDownLatch(1)

    override fun create(priority: Int, session: Int, cfg: EqConfig, mbcPassthrough: Boolean): DpPort {
        gate?.let {
            enteredGate.countDown()
            it.await(10, TimeUnit.SECONDS)
        }
        if (failures.isNotEmpty()) throw failures.removeAt(0)
        return FakePort(cfg, mbcPassthrough, inherited, clampGainTo).also { ports += it }
    }
}

internal fun awaitCondition(timeoutMs: Long = 5_000, cond: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!cond()) {
        if (System.nanoTime() > end) throw AssertionError("condition not met within ${timeoutMs}ms")
        Thread.sleep(5)
    }
}
