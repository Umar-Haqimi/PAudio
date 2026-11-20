package my.umarh.paudio.audio.tone

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.StereoBuffer
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StreamSchedulerTest {
    private val rate = 48_000

    private fun sine(frames: Int, hz: Double = 1000.0, amp: Float = 0.5f): StereoBuffer {
        val s = FloatArray(frames) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
        return StereoBuffer(s, s.copyOf(), rate)
    }

    private fun drain(sch: StreamScheduler, chunks: Int, chunk: Int = 480): FloatArray {
        val out = FloatArray(2 * chunk * chunks)
        val tmp = FloatArray(2 * chunk)
        for (c in 0 until chunks) {
            sch.fill(tmp, chunk)
            tmp.copyInto(out, 2 * chunk * c)
        }
        return out
    }

    @Test
    fun emitsDigitalSilenceWhenNothingQueued() {
        val out = drain(StreamScheduler(rate), 3)
        assertThat(out.all { it == 0f }).isTrue()
    }

    @Test
    fun stimulusIsEmittedContiguouslyThenSilenceAndPositionsAreExact() {
        val sch = StreamScheduler(rate)
        val st = Stimulus(sine(1000))
        drain(sch, 1) // 480 frames of silence first
        sch.enqueue(st)
        val out = drain(sch, 4) // 1920 frames
        assertThat(st.startFrame).isEqualTo(480)
        assertThat(st.endFrame).isEqualTo(480 + 1000)
        for (i in 0 until 1000) assertThat(out[2 * i]).isEqualTo(st.buffer.left[i])
        for (i in 1000 until 1920) assertThat(out[2 * i]).isEqualTo(0f)
    }

    @Test
    fun backToBackStimuliAreGapless() {
        val sch = StreamScheduler(rate)
        val a = Stimulus(sine(700))
        val b = Stimulus(sine(700))
        sch.enqueue(a)
        sch.enqueue(b)
        drain(sch, 4)
        assertThat(a.endFrame).isEqualTo(700)
        assertThat(b.startFrame).isEqualTo(700)
        assertThat(b.endFrame).isEqualTo(1400)
    }

    @Test
    fun earlyStopRampsDownWithoutDiscontinuity() {
        val sch = StreamScheduler(rate, rampMs = 25)
        val st = Stimulus(sine(48_000))
        sch.enqueue(st)
        drain(sch, 10) // 4800 frames in
        assertThat(sch.requestStop()).isTrue()
        val out = drain(sch, 6) // 2880 frames
        val rampFrames = 25 * rate / 1000
        // ends exactly at ramp end (inclusive) and is silent afterwards
        assertThat(st.endFrame).isEqualTo(4800L + rampFrames + 1)
        for (i in (rampFrames + 1) until 2880) assertThat(out[2 * i]).isEqualTo(0f)
        // No step: max consecutive difference bounded by the 1 kHz sine's own slope (2*pi*f/fs*amp ~ 0.065).
        var prev = (0.5 * sin(2 * PI * 1000.0 * 4799 / rate)).toFloat()
        var maxStep = 0f
        for (i in 0 until 2880) {
            maxStep = maxOf(maxStep, abs(out[2 * i] - prev))
            prev = out[2 * i]
        }
        assertThat(maxStep).isLessThan(0.08f)
        // The ramp ends on exact zero gain.
        assertThat(out[2 * rampFrames]).isEqualTo(0f)
    }

    @Test
    fun stopWithNothingPlayingIsNoOpAndCancelQueuedNeverStarts() {
        val sch = StreamScheduler(rate)
        assertThat(sch.requestStop()).isFalse()
        val st = Stimulus(sine(100))
        sch.enqueue(st)
        assertThat(sch.cancel(st)).isTrue()
        drain(sch, 1)
        assertThat(st.startFrame).isEqualTo(-1)
    }

    @Test
    fun stopLongerThanStimulusJustEndsNormally() {
        val sch = StreamScheduler(rate)
        val st = Stimulus(sine(300)) // shorter than the 1200-frame ramp
        sch.enqueue(st)
        drain(sch, 1, chunk = 100)
        sch.requestStop()
        drain(sch, 4, chunk = 100)
        assertThat(st.endFrame).isEqualTo(300)
    }

    @Test
    fun pcm16NeverDithersExactZero() {
        val dst = ShortArray(10_000) { 5 }
        Pcm16.convert(FloatArray(10_000), dst, 10_000)
        assertThat(dst.all { it == 0.toShort() }).isTrue()
    }

    @Test
    fun pcm16ConversionClampsAndDithersAtMostOneLsb() {
        val src = floatArrayOf(0f, 0.5f, 1.5f, -1.5f)
        val dst = ShortArray(4)
        Pcm16.convert(src, dst, 4)
        assertThat(abs(dst[0].toInt())).isAtMost(1)
        assertThat(abs(dst[1] - 16383)).isAtMost(2)
        assertThat(dst[2]).isEqualTo(32767.toShort())
        assertThat(dst[3]).isEqualTo((-32768).toShort())
    }
}
