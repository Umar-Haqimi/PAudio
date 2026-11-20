package my.umarh.paudio.audio.tone

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.core.model.StereoBuffer
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Sink that "plays" in real time: each write of N frames takes N/rate seconds, head = frames written. */
private class RealTimeSink(val rate: Int) : PcmSink {
    private val written = AtomicLong()
    val samples = java.util.Collections.synchronizedList(ArrayList<Float>())
    @Volatile var released = 0
    @Volatile var stopped = 0

    override fun write(interleaved: FloatArray, frames: Int): Int {
        Thread.sleep(maxOf(1L, frames * 1000L / rate / 2)) // 2x real time to keep tests short
        for (i in 0 until frames) samples += interleaved[2 * i]
        written.addAndGet(frames.toLong())
        return frames
    }

    override val playedFrames: Long get() = written.get()
    override fun start() {}
    override fun stop() { stopped++ }
    override fun release() { released++ }
}

class StreamEngineTest {
    private val rate = 48_000
    private fun tone(frames: Int) = StereoBuffer(FloatArray(frames) { 0.5f }, FloatArray(frames) { 0.5f }, rate)

    @Test
    fun playReturnsAfterStimulusPlayedAndStreamKeepsRunning() = runBlocking {
        val sink = RealTimeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        withTimeout(5_000) { e.play(tone(4800)) }
        val after = sink.playedFrames
        Thread.sleep(50)
        assertThat(sink.playedFrames).isGreaterThan(after) // still streaming silence
        e.close()
    }

    @Test
    fun playingFlagIsTrueDuringStimulusOnly() = runBlocking {
        val sink = RealTimeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val seen = async(Dispatchers.Default) {
            var sawTrue = false
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                if (e.playing.value) sawTrue = true
                Thread.sleep(1)
            }
            sawTrue
        }
        e.play(tone(48_000))
        assertThat(e.playing.value).isFalse()
        assertThat(seen.await()).isTrue()
        e.close()
    }

    @Test
    fun cancellingPlayRampsToSilenceAndCloseIsIdempotent() = runBlocking {
        val sink = RealTimeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val job = async(Dispatchers.Default) { e.play(tone(480_000)) }
        Thread.sleep(100)
        job.cancelAndJoin()
        Thread.sleep(150) // ramp (25 ms) + margin
        val n = sink.samples.size
        val tail = sink.samples.subList(n - 2000, n)
        assertThat(tail.all { it == 0f }).isTrue()
        assertThat(e.playing.value).isFalse()
        e.close()
        e.close()
        assertThat(sink.released).isEqualTo(1)
    }

    @Test
    fun stopCurrentCompletesPendingPlayNormally() = runBlocking {
        val sink = RealTimeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val play = async(Dispatchers.Default) { e.play(tone(480_000)) }
        Thread.sleep(100)
        withTimeout(2_000) { e.stopCurrent() }
        withTimeout(2_000) { play.await() }
        e.close()
    }

    @Test
    fun closeWhilePlayingFailsPlayWithCancellationAndPlayAfterCloseThrows() = runBlocking {
        val sink = RealTimeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val play = async(Dispatchers.Default) { runCatching { e.play(tone(480_000)) } }
        Thread.sleep(50)
        e.close()
        assertThat(play.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        var threw = false
        try { e.play(tone(10)) } catch (_: IllegalStateException) { threw = true }
        assertThat(threw).isTrue()
    }
}
