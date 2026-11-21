package my.umarh.paudio.audio.tone

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.core.model.StereoBuffer
import org.junit.Test

/** SPEC §2.2 trap 5 (M12): one continuous stream for a whole session, never one sink per beep. */
class OneStreamTrapTest {
    private val rate = 48_000

    @Test
    fun manyStimuliAndGapsUseTheSingleSinkOpenedUpFrontAndReleaseItOnlyOnClose() = runBlocking {
        val sink = FakeSink(rate)
        var factoryCalls = 0
        val e = StreamEngine(sink, rate, sinkFactory = { factoryCalls++; null })
        e.start()
        repeat(12) {
            withTimeout(5_000) { e.play(StereoBuffer(FloatArray(1200) { 0.3f }, FloatArray(1200) { 0.3f }, rate)) }
            assertThat(sink.released).isEqualTo(0) // never torn down between pulses
        }
        assertThat(factoryCalls).isEqualTo(0) // no new stream was created for any pulse
        e.close()
        assertThat(sink.released).isEqualTo(1)
        assertThat(factoryCalls).isEqualTo(0)
    }
}
