package my.umarh.paudio.core.audiometry

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class StimulusSynthTest {
    private fun active(b: my.umarh.paudio.core.model.StereoBuffer, ear: Ear) = b.channel(ear)
    private fun silent(b: my.umarh.paudio.core.model.StereoBuffer, ear: Ear) = b.channel(ear.other)

    @Test
    fun goldenSet7() {
        val g = Golden.load("stimulus_synthesis")
        val tolSamples = g.getValue("tolerance").jsonObject.getValue("samples").dbl()
        val tolDb = g.getValue("tolerance").jsonObject.getValue("statsDb").dbl()
        assertThat(g.cases().size).isEqualTo(6)
        for (c in g.cases()) {
            val o = c.jsonObject
            val name = o.getValue("name").str()
            val f = o.getValue("freqHz").int()
            val fs = o.getValue("sampleRate").int()
            val ear = Ear.valueOf(o.getValue("ear").str())
            val level = o.getValue("levelDbfs").dbl()
            val expected = o.getValue("samples").doubles()
            val pulse = StimulusSynth.singlePulse(ear, f, level, fs)
            val three = StimulusSynth.threePulse(ear, f, level, fs)
            assertThat(pulse.frames).isEqualTo(o.getValue("frames").int())
            for (buf in listOf(pulse, three)) {
                val ch = active(buf, ear)
                for (i in expected.indices) {
                    assertThat(abs(ch[i] - expected[i])).isAtMost(tolSamples)
                }
                assertThat(silent(buf, ear).all { it == 0.0f }).isTrue()
            }
            val x = active(pulse, ear)
            val peak = x.maxOf { abs(it) }.toDouble()
            val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
            assertThat(abs(20 * log10(peak) - o.getValue("peakDb").dbl())).isAtMost(tolDb)
            assertThat(abs(20 * log10(rms) - o.getValue("rmsDb").dbl())).isAtMost(tolDb)
            val steady = steadyPeakDb(x, fs)
            assertThat(abs(steady - o.getValue("steadyPeakDb").dbl())).isAtMost(tolDb)
            val oob = outOfBandRelDb(x, f, fs)
            assertThat(abs(oob - o.getValue("outOfBandRelDb").dbl())).isAtMost(tolDb)
            assertThat(abs(x.first() - o.getValue("firstSample").dbl())).isAtMost(tolSamples)
            assertThat(abs(x.last() - o.getValue("lastSample").dbl())).isAtMost(tolSamples)
            println("set7 $name ok (outOfBand ${"%.1f".format(oob)} dB)")
        }
    }

    private fun steadyPeakDb(x: FloatArray, fs: Int): Double {
        var m = 0.0
        for (n in x.indices) {
            if (n.toDouble() / fs >= 0.025 && (x.size - 1 - n).toDouble() / fs >= 0.025) m = max(m, abs(x[n].toDouble()))
        }
        return 20 * log10(m)
    }

    @Test
    fun qualityOnEveryGridFrequencyAndSampleRate() {
        for (fs in listOf(44100, 48000)) {
            for (f in FrequencyGrid.TEST_FREQUENCIES_HZ) {
                for (level in listOf(-60.0, -40.0, -10.0)) {
                    val ear = if (f % 2000 == 0) Ear.LEFT else Ear.RIGHT
                    val s = StimulusSynth.threePulse(ear, f, level, fs)
                    val ch = active(s, ear)
                    assertThat(s.frames).isEqualTo(3 * StimulusSynth.msToSamples(225, fs) + 2 * StimulusSynth.msToSamples(200, fs))
                    // Ramps start and end within 1e-4 of zero (absolute; target amplitudes are <= 0.316).
                    val n = StimulusSynth.msToSamples(225, fs)
                    val gap = StimulusSynth.msToSamples(200, fs)
                    for (k in 0 until 3) {
                        val off = k * (n + gap)
                        assertThat(abs(ch[off])).isAtMost(1e-4f)
                        assertThat(abs(ch[off + n - 1])).isAtMost(1e-4f)
                    }
                    // Steady-state peak within ±0.05 dB of target (check the first pulse's plateau).
                    val steady = steadyPeakDb(ch.copyOfRange(0, n), fs)
                    assertThat(abs(steady - level)).isAtMost(0.05)
                    // Out-of-band energy ≤ −50 dB relative to in-band, over the whole 3-pulse stimulus.
                    val oob = outOfBandRelDb(ch, f, fs)
                    assertThat(oob).isAtMost(-50.0)
                    // Opposite channel exactly 0.0.
                    assertThat(silent(s, ear).all { it == 0.0f }).isTrue()
                    // Gaps are digital silence.
                    for (i in n until n + gap) assertThat(ch[i]).isEqualTo(0.0f)
                }
            }
        }
    }

    @Test
    fun pureBelowWarbleFromFourKilohertz() {
        // 3 kHz: instantaneous frequency constant (pure); 4 kHz: warbled (phase differs from the pure closed form).
        assertThat(StimulusSynth.usesWarble(3000)).isFalse()
        assertThat(StimulusSynth.usesWarble(4000)).isTrue()
        val t = 0.0625 // quarter modulation period: sin = 1
        val pure = StimulusSynth.phase(4000.0, false, t)
        val warb = StimulusSynth.phase(4000.0, true, t)
        assertThat(warb - pure).isWithin(1e-9).of(0.05 * 4000.0 / 8.0 * (1.0 - kotlin.math.cos(2 * Math.PI * 8.0 * t)))
    }

    @Test
    fun earlyStopRampsDownToExactZero() {
        val s = StimulusSynth.threePulse(Ear.RIGHT, 1000, -20.0, 48000)
        val stop = 5000
        val e = StimulusSynth.earlyStop(s, stop)
        val ramp = StimulusSynth.msToSamples(25, 48000)
        assertThat(e.frames).isEqualTo(stop + ramp)
        assertThat(e.right[e.frames - 1]).isEqualTo(0.0f)
        assertThat(e.left.all { it == 0.0f }).isTrue()
        for (i in 0 until stop) assertThat(e.right[i]).isEqualTo(s.right[i])
        // Monotone non-increasing envelope factor: no sample exceeds the source magnitude.
        for (i in stop until e.frames) assertThat(abs(e.right[i])).isAtMost(abs(s.right[i]) + 1e-9f)
        // Stopping past the end returns the buffer unchanged.
        assertThat(StimulusSynth.earlyStop(s, s.frames)).isSameInstanceAs(s)
    }

    @Test
    fun calibrationStimulusIsBinauralOneKilohertz() {
        val c = StimulusSynth.calibrationPulse(-50.0, 48000)
        assertThat(c.left).isEqualTo(c.right)
        assertThat(c.left.maxOf { abs(it) }.toDouble()).isWithin(1e-4).of(Math.pow(10.0, -50.0 / 20.0))
        val cycle = StimulusSynth.calibrationCycle(-50.0, 48000)
        assertThat(cycle.frames).isEqualTo(48000 * 450 / 1000)
    }

    @Test
    fun loopingToneIsOnePulsePlusGap() {
        val l = StimulusSynth.loopingPulsedTone(Ear.LEFT, 8000, -40.0, 44100)
        assertThat(l.frames).isEqualTo(StimulusSynth.msToSamples(225, 44100) + StimulusSynth.msToSamples(200, 44100))
        assertThat(l.right.all { it == 0.0f }).isTrue()
        assertThat(l.left.last()).isEqualTo(0.0f)
    }
}
