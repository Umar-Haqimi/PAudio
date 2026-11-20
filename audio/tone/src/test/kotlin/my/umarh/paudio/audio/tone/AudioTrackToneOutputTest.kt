package my.umarh.paudio.audio.tone

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.core.model.StereoBuffer
import org.junit.Test

/** Uses the internal sink factory; AudioTrack and Process priority are never touched on the JVM (Robolectric-free). */
class AudioTrackToneOutputTest {
    private val rate = 48_000
    private fun tone(frames: Int) = StereoBuffer(FloatArray(frames) { 0.5f }, FloatArray(frames) { 0.5f }, rate)

    private fun output(sinks: MutableList<FakeSink>, failFirstAfter: Int = Int.MAX_VALUE) =
        AudioTrackToneOutput(sinkFactory = { r ->
            FakeSink(r, failAfterWrites = if (sinks.isEmpty()) failFirstAfter else Int.MAX_VALUE).also { sinks += it }
        })

    @Test
    fun playingFlowIsOneStableFlowAcrossCloseAndReopen() = runBlocking {
        val sinks = mutableListOf<FakeSink>()
        val out = output(sinks)
        val flow = out.playing // collected reference taken before everything
        out.open(rate)
        out.close()
        out.close() // idempotent
        out.open(rate)
        val seen = java.util.concurrent.atomic.AtomicBoolean()
        val watcher = Thread {
            val end = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < end) { if (flow.value) seen.set(true); Thread.sleep(1) }
        }.also { it.start() }
        withTimeout(3_000) { out.play(tone(9_600)) }
        watcher.join()
        assertThat(seen.get()).isTrue()
        assertThat(flow.value).isFalse()
        assertThat(sinks).hasSize(2)
        out.close()
    }

    @Test
    fun failedEngineIsRebuiltByOpenAndPlayOnFailedThrows() = runBlocking {
        var calls = 0
        // call 1: sink that dies after 1 write; call 2 (engine's recovery attempt): unavailable; call 3+: healthy.
        val out = AudioTrackToneOutput(sinkFactory = { r ->
            when (++calls) {
                1 -> FakeSink(r, failAfterWrites = 1)
                2 -> null
                else -> FakeSink(r)
            }
        })
        out.open(rate)
        Thread.sleep(150)
        var threw = false
        try { withTimeout(3_000) { out.play(tone(10)) } } catch (_: IllegalStateException) { threw = true }
        assertThat(threw).isTrue()
        out.open(rate) // failed engine is replaced
        withTimeout(3_000) { out.play(tone(480)) }
        out.close()
    }
}
