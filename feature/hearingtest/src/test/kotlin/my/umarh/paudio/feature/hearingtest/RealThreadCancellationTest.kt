package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.audio.tone.PcmSink
import my.umarh.paudio.audio.tone.StreamEngine
import my.umarh.paudio.core.model.StereoBuffer
import my.umarh.paudio.core.model.TestMode
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** A sink that "plays" at 10x real time: each write of N frames takes ~N/rate/10 s; the head follows the writes. */
private class RealTimeSink(val rate: Int) : PcmSink {
    private val written = AtomicLong()
    val released = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)

    override fun write(interleaved: FloatArray, frames: Int): Int {
        Thread.sleep(maxOf(1L, frames * 1000L / rate / 10))
        written.addAndGet(frames.toLong())
        return frames
    }

    override val playedFrames: Long get() = written.get()
    override fun start() {}
    override fun stop() {
        stopped.set(true)
    }

    override fun release() {
        released.set(true)
    }
}

/** The real [StreamEngine] (writer thread, scheduler, ramp-down on cancel) behind the tone interface. */
private class StreamBackedTone(private val bypassHeld: () -> Boolean, private val log: EventLog) : HookedTone {
    override val violations = java.util.concurrent.CopyOnWriteArrayList<String>()
    override val played = java.util.concurrent.CopyOnWriteArrayList<StereoBuffer>()
    override val openCount = AtomicInteger()
    override val closeCount = AtomicInteger()
    @Volatile override var onPlay: (StereoBuffer) -> Unit = {}
    @Volatile var engine: StreamEngine? = null
    @Volatile var sink: RealTimeSink? = null
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing
    override val isOpen: Boolean get() = engine?.isClosed == false

    override suspend fun open(sampleRate: Int) {
        if (!bypassHeld()) violations += "open without bypass"
        engine?.close()
        val s = RealTimeSink(sampleRate)
        sink = s
        engine = StreamEngine(s, sampleRate, playingFlow = _playing).also { it.start() }
        openCount.incrementAndGet()
        log.add("tone.open")
    }

    private fun e() = engine ?: error("not open")

    override suspend fun play(stimulus: StereoBuffer) {
        if (!bypassHeld()) violations += "play without bypass"
        played += stimulus
        onPlay(stimulus)
        e().play(stimulus)
    }

    override suspend fun playSilence(ms: Int) {
        if (!bypassHeld()) violations += "playSilence without bypass"
        val n = e().sampleRate.toLong() * ms / 1000
        e().play(StereoBuffer(FloatArray(n.toInt()), FloatArray(n.toInt()), e().sampleRate))
    }

    override suspend fun stopCurrent() = e().stopCurrent()

    override fun close() {
        engine?.let { log.add("tone.close"); it.close() }
        closeCount.incrementAndGet()
    }
}

/**
 * SPEC §5.3 / §7.2 on REAL threads: the session runs on a dedicated single-thread executor, the tone is the real
 * [StreamEngine] writer thread over a real-time sink, and cancellation hits at random wall-clock moments (also while
 * paused). Not just unconfined dispatchers: the track must always be closed, the volume restored and the bypass
 * token released, and no writer thread may outlive the session.
 */
class RealThreadCancellationTest {
    @Test
    fun randomCancellationOnRealThreadsAlwaysCleansUp() {
        val rng = Random(777)
        repeat(18) { i ->
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "paudio-hearingtest-test").apply { isDaemon = true } }
            val dispatcher = executor.asCoroutineDispatcher()
            val rig = Rig(
                TestMode.QUICK, { System.nanoTime() / 1_000_000 }, dispatcher, timed = false, seed = i.toLong(),
                toneFactory = { held, log -> StreamBackedTone(held, log) },
            )
            Brain(rig, normalListener(i.toLong())).install()
            rig.session.start()
            Thread.sleep(rng.nextLong(0, 2500))
            when (i % 4) {
                1 -> rig.devices.set(null)
                2 -> rig.focus.lose(FocusEvent.LOST)
                3 -> rig.volume.externalChange(2)
                else -> Unit
            }
            if (i % 4 != 0) Thread.sleep(rng.nextLong(20, 300))
            runBlocking { withTimeout(10_000) { rig.session.cancelAndJoin() } }

            val tone = rig.tone as StreamBackedTone
            assertThat(tone.violations).isEmpty()
            assertThat(rig.bypass.bypassed.value).isFalse()
            assertThat(rig.trace.openCount).isEqualTo(0)
            tone.engine?.let { assertThat(it.isClosed).isTrue() }
            tone.sink?.let {
                assertThat(it.released.get()).isTrue()
                assertThat(it.stopped.get()).isTrue()
            }
            val expected = if (i % 4 == 3) 2 else rig.originalVolume
            assertThat(rig.volume.index()).isEqualTo(expected)
            if (tone.openCount.get() > 0) {
                assertThat(rig.log.indexOf("tone.close")).isLessThan(rig.log.indexOf("bypass.release"))
            }
            dispatcher.close()
        }
        // no writer thread survives
        val deadline = System.currentTimeMillis() + 3_000
        fun writers() = Thread.getAllStackTraces().keys.count { it.name == "paudio-tone-writer" && it.isAlive }
        while (writers() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertThat(writers()).isEqualTo(0)
    }
}
