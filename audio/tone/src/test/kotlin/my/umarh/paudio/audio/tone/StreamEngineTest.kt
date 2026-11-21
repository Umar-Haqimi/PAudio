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

class StreamEngineTest {
    private val rate = 48_000
    private fun tone(frames: Int) = StereoBuffer(FloatArray(frames) { 0.5f }, FloatArray(frames) { 0.5f }, rate)

    private suspend fun failure(block: suspend () -> Unit): Throwable? =
        try { withTimeout(3_000) { block() }; null } catch (e: Throwable) { e }

    @Test
    fun playReturnsAfterStimulusPlayedAndStreamKeepsRunning() = runBlocking {
        val sink = FakeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        withTimeout(5_000) { e.play(tone(4800)) }
        val after = sink.framesWritten
        Thread.sleep(50)
        assertThat(sink.framesWritten).isGreaterThan(after) // still streaming silence
        e.close()
    }

    @Test
    fun playingFlagIsTrueDuringStimulusOnly() = runBlocking {
        val sink = FakeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val seen = async(Dispatchers.Default) {
            var sawTrue = false
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) {
                if (e.playing.value) sawTrue = true
                Thread.sleep(1)
            }
            sawTrue
        }
        e.play(tone(24_000))
        assertThat(e.playing.value).isFalse()
        assertThat(seen.await()).isTrue()
        e.close()
    }

    @Test
    fun playCompletesOnlyOnceHeadReachesEndAndPlayingStaysTrueThroughLag() = runBlocking {
        val lag = 9_600 // 200 ms behind the writes
        val sink = FakeSink(rate, lagFrames = lag)
        val e = StreamEngine(sink, rate)
        e.start()
        val stimulus = 4_800
        val t = async(Dispatchers.Default) {
            e.play(tone(stimulus))
            sink.playedFrames to e.playing.value
        }
        // Sample `playing` until play returns; once true it must stay true until completion.
        var seenTrue = false
        var droppedEarly = false
        while (!t.isCompleted) {
            val p = e.playing.value
            if (p) seenTrue = true else if (seenTrue && sink.playedFrames < stimulus) droppedEarly = true
            Thread.sleep(1)
        }
        val (head, playingAtEnd) = t.await()
        assertThat(seenTrue).isTrue()
        assertThat(droppedEarly).isFalse()
        assertThat(head).isAtLeast(stimulus.toLong()) // started at frame 0 -> ends at 4800
        assertThat(playingAtEnd).isFalse()
        e.close()
    }

    @Test
    fun explicitSilenceNeverSetsPlaying() = runBlocking {
        val e = StreamEngine(FakeSink(rate), rate)
        e.start()
        val seen = async(Dispatchers.Default) {
            var sawTrue = false
            val end = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < end) {
                if (e.playing.value) sawTrue = true
                Thread.sleep(1)
            }
            sawTrue
        }
        withTimeout(5_000) { e.playSilence(400) }
        assertThat(seen.await()).isFalse()
        e.close()
    }

    /**
     * The Thorough dial loop (pulse buffer, then a separate silent gap buffer): `playing` drops for the gap, and is
     * already false whenever a pulse's play() has returned (published before the caller is woken; trap #20).
     */
    @Test
    fun pulseThenSilentGapDropsPlayingForTheGap() = runBlocking {
        val e = StreamEngine(FakeSink(rate), rate)
        e.start()
        var falseInGap = 0
        var trueAfterPulseReturned = 0
        val gapWatcher = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = async(Dispatchers.Default) {
            val end = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < end) {
                if (gapWatcher.get() && !e.playing.value) falseInGap++
                Thread.sleep(1)
            }
        }
        withTimeout(5_000) {
            repeat(3) {
                e.play(tone(4_800))
                if (e.playing.value) trueAfterPulseReturned++
                gapWatcher.set(true)
                e.playSilence(150)
                gapWatcher.set(false)
            }
        }
        sampler.await()
        assertThat(trueAfterPulseReturned).isEqualTo(0)
        assertThat(falseInGap).isGreaterThan(0)
        e.close()
    }

    @Test
    fun cancellingPlayRampsToSilenceAndCloseIsIdempotent() = runBlocking {
        val sink = FakeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val job = async(Dispatchers.Default) { e.play(tone(480_000)) }
        Thread.sleep(100)
        job.cancelAndJoin()
        Thread.sleep(150) // ramp (25 ms) + margin
        // Copy under the list's lock: the writer thread keeps appending silence (a live subList view throws CME).
        val tail = synchronized(sink.samples) { sink.samples.takeLast(2000) }
        assertThat(tail.all { it == 0f }).isTrue()
        assertThat(e.playing.value).isFalse()
        e.close()
        e.close()
        assertThat(sink.released).isEqualTo(1)
    }

    @Test
    fun stopCurrentCompletesPendingPlayNormally() = runBlocking {
        val e = StreamEngine(FakeSink(rate), rate)
        e.start()
        val play = async(Dispatchers.Default) { e.play(tone(480_000)) }
        Thread.sleep(100)
        withTimeout(2_000) { e.stopCurrent() }
        withTimeout(2_000) { play.await() }
        e.close()
    }

    @Test
    fun closeWhilePlayingFailsPlayWithCancellationAndPlayAfterCloseThrows() = runBlocking {
        val e = StreamEngine(FakeSink(rate), rate)
        e.start()
        val play = async(Dispatchers.Default) { runCatching { e.play(tone(480_000)) } }
        Thread.sleep(50)
        e.close()
        assertThat(play.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(failure { e.play(tone(10)) }).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun writeErrorMarksEngineFailedAndPlayThrowsInsteadOfHanging() = runBlocking {
        val sink = FakeSink(rate, failAfterWrites = 2, failCode = -6)
        val e = StreamEngine(sink, rate) // no factory: cannot recover
        e.start()
        Thread.sleep(100)
        assertThat(e.isClosed).isTrue()
        assertThat(failure { e.play(tone(100)) }).isInstanceOf(IllegalStateException::class.java)
        assertThat(sink.released).isAtLeast(1)
        e.close() // still safe
    }

    @Test
    fun writeErrorWhilePlayingFailsThePendingPlay() = runBlocking {
        val sink = FakeSink(rate, failAfterWrites = 30, failCode = -3)
        val e = StreamEngine(sink, rate)
        e.start()
        val err = failure { e.play(tone(480_000)) }
        assertThat(err).isInstanceOf(IllegalStateException::class.java)
        assertThat(e.isClosed).isTrue()
    }

    @Test
    fun deadObjectRecreatesSinkOnceAndStreamSurvives() = runBlocking {
        val second = FakeSink(rate)
        var created = 0
        val e = StreamEngine(FakeSink(rate, failAfterWrites = 30, failCode = -6), rate, sinkFactory = {
            created++
            second
        })
        e.start()
        // The stimulus in flight when the track died is lost...
        assertThat(failure { e.play(tone(480_000)) }).isInstanceOf(IllegalStateException::class.java)
        assertThat(e.isClosed).isFalse()
        // ...but the stream lives and the next stimulus plays on the new sink.
        withTimeout(3_000) { e.play(tone(2_400)) }
        assertThat(created).isEqualTo(1)
        assertThat(e.sinkRecreations).isEqualTo(1)
        assertThat(synchronized(second.samples) { second.samples.any { it == 0.5f } }).isTrue()
        e.close()
    }

    @Test
    fun deadObjectWithFailingFactoryFailsEngine() = runBlocking {
        val e = StreamEngine(FakeSink(rate, failAfterWrites = 5), rate, sinkFactory = { null })
        e.start()
        Thread.sleep(100)
        assertThat(e.isClosed).isTrue()
        assertThat(failure { e.play(tone(10)) }).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun invalidStimulusDoesNotLeaveAnOrphanWaiter() = runBlocking {
        val e = StreamEngine(FakeSink(rate), rate)
        e.start()
        val bad = StereoBuffer(FloatArray(10), FloatArray(10), 44_100)
        assertThat(failure { e.play(bad) }).isInstanceOf(IllegalArgumentException::class.java)
        withTimeout(3_000) { e.play(tone(480)) } // engine unaffected
        e.close()
    }

    @Test
    fun playRacingCloseNeverHangs() = runBlocking {
        repeat(30) {
            val e = StreamEngine(FakeSink(rate), rate)
            e.start()
            val p = async(Dispatchers.Default) { runCatching { e.play(tone(48_000)) } }
            if (it % 2 == 0) Thread.sleep(1)
            e.close()
            withTimeout(3_000) { p.await() } // must complete (normally or exceptionally)
        }
    }

    @Test
    fun headMovingBackwardsFailsPendingAndRebases() = runBlocking {
        val sink = FakeSink(rate)
        val e = StreamEngine(sink, rate)
        e.start()
        val p = async(Dispatchers.Default) { runCatching { e.play(tone(480_000)) } }
        Thread.sleep(100)
        sink.resetHead()
        assertThat(withTimeout(3_000) { p.await() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(e.isClosed).isFalse()
        withTimeout(3_000) { e.play(tone(2_400)) }
        e.close()
    }
}
