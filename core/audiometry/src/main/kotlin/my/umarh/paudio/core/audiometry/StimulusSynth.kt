package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.StereoBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hearing-test stimulus synthesis (SPEC §2.1.2, §2.2 #4, §4). All maths in Double; output is float PCM.
 *
 * Conventions (frozen by golden set 7, docs/VECTORS.md):
 *  - Level L (dBFS) = 20·log10(peak amplitude); peak amplitude = 10^(L/20).
 *  - A *pulse* is 225 ms with 25 ms raised-cosine on/off ramps `r(t) = 0.5·(1 − cos(π·t/25 ms))`, the off ramp
 *    mirrored so that the first and last samples of a pulse are exactly 0. Sample count `N = (225·fs + 500)/1000`
 *    (integer round-half-up); sample n is at pulse-local time `t = n/fs` (every pulse restarts at phase 0).
 *  - f < 4 kHz: pure sine, `φ(t) = 2π·f·t`.
 *  - f ≥ 4 kHz: ±5 % FM warble at 8 Hz, `f_inst(t) = f·(1 + 0.05·sin(2π·8·t))`, closed-form phase
 *    `φ(t) = 2π·f·t + (0.05·f/8)·(1 − cos(2π·8·t))` (the analytic integral of 2π·f_inst, φ(0) = 0).
 *  - The opposite channel is exactly 0.0f.
 *  - Stimulus = 3 pulses with 200 ms gaps (1075 ms).
 */
object StimulusSynth {
    const val PULSE_MS = 225
    const val RAMP_MS = 25
    const val GAP_MS = 200
    const val PULSE_COUNT = 3
    const val WARBLE_DEPTH = 0.05
    const val WARBLE_RATE_HZ = 8.0

    /** Total duration of the 3-pulse stimulus in ms (3·225 + 2·200 = 1075). */
    const val STIMULUS_MS = PULSE_COUNT * PULSE_MS + (PULSE_COUNT - 1) * GAP_MS

    /** Integer ms → sample count, round half up (exact for the 200 ms and 25 ms values at 44.1/48 kHz). */
    fun msToSamples(ms: Int, sampleRate: Int): Int = ((ms.toLong() * sampleRate + 500L) / 1000L).toInt()

    fun amplitude(levelDbfs: Double): Double = 10.0.pow(levelDbfs / 20.0)

    fun usesWarble(freqHz: Int): Boolean = freqHz >= FrequencyGrid.WARBLE_FROM_HZ

    /** Instantaneous-phase closed form; `t` is pulse-local time in seconds. */
    fun phase(freqHz: Double, warble: Boolean, t: Double): Double {
        val base = 2.0 * PI * freqHz * t
        return if (warble) {
            base + (WARBLE_DEPTH * freqHz / WARBLE_RATE_HZ) * (1.0 - cos(2.0 * PI * WARBLE_RATE_HZ * t))
        } else {
            base
        }
    }

    /** One pulse (envelope included) as a mono Double array. */
    fun pulseSamples(freqHz: Int, levelDbfs: Double, sampleRate: Int): DoubleArray {
        val n = msToSamples(PULSE_MS, sampleRate)
        val amp = amplitude(levelDbfs)
        val warble = usesWarble(freqHz)
        val rampSec = RAMP_MS / 1000.0
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val tOn = i.toDouble() / sampleRate
            val tOff = (n - 1 - i).toDouble() / sampleRate
            val rOn = if (tOn < rampSec) 0.5 * (1.0 - cos(PI * tOn / rampSec)) else 1.0
            val rOff = if (tOff < rampSec) 0.5 * (1.0 - cos(PI * tOff / rampSec)) else 1.0
            out[i] = amp * rOn * rOff * sin(phase(freqHz.toDouble(), warble, tOn))
        }
        return out
    }

    private fun place(ear: Ear, mono: FloatArray, sampleRate: Int): StereoBuffer {
        val silent = FloatArray(mono.size)
        return if (ear == Ear.LEFT) StereoBuffer(mono, silent, sampleRate) else StereoBuffer(silent, mono, sampleRate)
    }

    /** A single 225 ms pulse in [ear]; the other channel is exactly 0.0f. */
    fun singlePulse(ear: Ear, freqHz: Int, levelDbfs: Double, sampleRate: Int): StereoBuffer {
        val p = pulseSamples(freqHz, levelDbfs, sampleRate)
        return place(ear, FloatArray(p.size) { p[it].toFloat() }, sampleRate)
    }

    /** The Quick-test stimulus: 3 pulses × 225 ms, 200 ms gaps. */
    fun threePulse(ear: Ear, freqHz: Int, levelDbfs: Double, sampleRate: Int): StereoBuffer {
        val p = pulseSamples(freqHz, levelDbfs, sampleRate)
        val gap = msToSamples(GAP_MS, sampleRate)
        val total = PULSE_COUNT * p.size + (PULSE_COUNT - 1) * gap
        val mono = FloatArray(total)
        for (k in 0 until PULSE_COUNT) {
            val off = k * (p.size + gap)
            for (i in p.indices) mono[off + i] = p[i].toFloat()
        }
        return place(ear, mono, sampleRate)
    }

    /**
     * One loop period of the Thorough-test "pulsed tone": a 225 ms pulse followed by a 200 ms gap (425 ms).
     * ToneOutput repeats it seamlessly; re-synthesise with a new level when the dial moves.
     */
    fun loopingPulsedTone(ear: Ear, freqHz: Int, levelDbfs: Double, sampleRate: Int): StereoBuffer {
        val p = pulseSamples(freqHz, levelDbfs, sampleRate)
        val gap = msToSamples(GAP_MS, sampleRate)
        val mono = FloatArray(p.size + gap)
        for (i in p.indices) mono[i] = p[i].toFloat()
        return place(ear, mono, sampleRate)
    }

    /**
     * Early stop after a "heard" response (SPEC §4): keep [source] up to [stopFrame], then fade the continuing
     * signal to exactly 0 with a 25 ms raised-cosine ramp-down and end the buffer there. If [stopFrame] already
     * lies at/after the end the buffer is returned unchanged.
     */
    fun earlyStop(source: StereoBuffer, stopFrame: Int): StereoBuffer {
        require(stopFrame >= 0) { "stopFrame must be >= 0" }
        if (stopFrame >= source.frames) return source
        val rampLen = msToSamples(RAMP_MS, source.sampleRate)
        val end = minOf(source.frames, stopFrame + rampLen)
        val len = end - stopFrame
        fun cut(ch: FloatArray): FloatArray {
            val out = ch.copyOf(end)
            for (k in 0 until len) {
                val g = if (len <= 1) 0.0 else 0.5 * (1.0 + cos(PI * k / (len - 1)))
                out[stopFrame + k] = (ch[stopFrame + k] * g).toFloat()
            }
            return out
        }
        return StereoBuffer(cut(source.left), cut(source.right), source.sampleRate)
    }

    /** Calibration tone: binaural (both channels identical) 1 kHz pure pulse, 225 ms. */
    fun calibrationPulse(levelDbfs: Double, sampleRate: Int): StereoBuffer {
        val p = pulseSamples(1000, levelDbfs, sampleRate)
        val a = FloatArray(p.size) { p[it].toFloat() }
        return StereoBuffer(a, a.copyOf(), sampleRate)
    }

    /** One calibration step period (450 ms): the binaural pulse, then silence (matches the 450 ms ramp step). */
    fun calibrationCycle(levelDbfs: Double, sampleRate: Int): StereoBuffer {
        val pulse = calibrationPulse(levelDbfs, sampleRate)
        val total = msToSamples(Calibration.STEP_MS, sampleRate)
        return StereoBuffer(pulse.left.copyOf(total), pulse.right.copyOf(total), sampleRate)
    }
}
