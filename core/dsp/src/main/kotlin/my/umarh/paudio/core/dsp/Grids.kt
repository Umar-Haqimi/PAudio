package my.umarh.paudio.core.dsp

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

private const val LN2 = 0.6931471805599453

/** log2 in double precision. */
internal fun log2(x: Double): Double = ln(x) / LN2

/**
 * The shared 1/24-octave analysis grid (SPEC §2.1.3): `f_i = 20·2^(i/24)`, i = 0..239
 * (20 Hz … 19.70 kHz), double precision.
 */
object DspGrid {
    const val POINTS: Int = 240
    const val F0_HZ: Double = 20.0
    const val STEPS_PER_OCTAVE: Int = 24

    /** Frequency of grid point [i] in Hz. */
    fun freqHz(i: Int): Double = F0_HZ * 2.0.pow(i / STEPS_PER_OCTAVE.toDouble())

    /** All [POINTS] grid frequencies. */
    val gridHz: DoubleArray = DoubleArray(POINTS) { freqHz(it) }

    /**
     * Linear interpolation of per-grid-point [values] in log2(f); the end values are held outside
     * 20 Hz … [freqHz]([POINTS]−1) (SPEC §2.1.4 / DECISIONS §1, "hold ends").
     */
    fun interpolate(values: DoubleArray, fHz: Double): Double {
        require(values.size == POINTS) { "expected $POINTS grid values, got ${values.size}" }
        val pos = STEPS_PER_OCTAVE * log2(fHz / F0_HZ)
        if (pos <= 0.0) return values[0]
        if (pos >= POINTS - 1) return values[POINTS - 1]
        val i = floor(pos).toInt()
        val t = pos - i
        return values[i] + t * (values[i + 1] - values[i])
    }
}

/**
 * The 30 one-third-octave bands used by the loudness predictor (SPEC §2.1.5):
 * centres `f_b = 1000·2^(b/3)`, b = −16..13 (≈19.7 Hz … 20.2 kHz), edges `f_b·2^(±1/6)`.
 */
object ThirdOctaveBands {
    const val FIRST: Int = -16
    const val LAST: Int = 13
    const val COUNT: Int = LAST - FIRST + 1

    fun centerHz(index: Int): Double = 1000.0 * 2.0.pow((FIRST + index) / 3.0)
    fun lowEdgeHz(index: Int): Double = centerHz(index) * 2.0.pow(-1.0 / 6.0)
    fun highEdgeHz(index: Int): Double = centerHz(index) * 2.0.pow(1.0 / 6.0)

    val centersHz: DoubleArray = DoubleArray(COUNT) { centerHz(it) }
}
