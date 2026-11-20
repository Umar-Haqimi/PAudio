package my.umarh.paudio.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Magnitude of a biquad `(b0+b1 z⁻¹+b2 z⁻²)/(a0+a1 z⁻¹+a2 z⁻²)` on the unit circle, in dB. */
internal fun biquadMagnitudeDb(
    b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double, fHz: Double, fs: Double,
): Double {
    val w = 2.0 * PI * fHz / fs
    val c1 = cos(w)
    val s1 = sin(w)
    val c2 = cos(2.0 * w)
    val s2 = sin(2.0 * w)
    val nr = b0 + b1 * c1 + b2 * c2
    val ni = -(b1 * s1 + b2 * s2)
    val dr = a0 + a1 * c1 + a2 * c2
    val di = -(a1 * s1 + a2 * s2)
    return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
}

/**
 * RBJ "Audio EQ Cookbook" (W3C Note) biquad magnitude responses in dB, evaluated analytically
 * (SPEC §2.1.3, preference curve; golden set 1). Shelves use the Q form
 * (`alpha = sin(w0)/(2Q)`, shelf term `2·sqrt(A)·alpha`), `A = 10^(gainDb/40)`.
 */
object Rbj {
    const val DEFAULT_FS: Double = 48_000.0

    fun lowShelfDb(fHz: Double, fc: Double, q: Double, gainDb: Double, fs: Double = DEFAULT_FS): Double {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val t = 2.0 * sqrt(a) * alpha
        return biquadMagnitudeDb(
            a * ((a + 1) - (a - 1) * cw + t),
            2.0 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - t),
            (a + 1) + (a - 1) * cw + t,
            -2.0 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - t,
            fHz, fs,
        )
    }

    fun peakingDb(fHz: Double, fc: Double, q: Double, gainDb: Double, fs: Double = DEFAULT_FS): Double {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        return biquadMagnitudeDb(
            1.0 + alpha * a, -2.0 * cw, 1.0 - alpha * a,
            1.0 + alpha / a, -2.0 * cw, 1.0 - alpha / a,
            fHz, fs,
        )
    }

    fun highShelfDb(fHz: Double, fc: Double, q: Double, gainDb: Double, fs: Double = DEFAULT_FS): Double {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val t = 2.0 * sqrt(a) * alpha
        return biquadMagnitudeDb(
            a * ((a + 1) + (a - 1) * cw + t),
            -2.0 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - t),
            (a + 1) - (a - 1) * cw + t,
            2.0 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - t,
            fHz, fs,
        )
    }
}

/**
 * Shared preference curve `g_p(f) = bass + presence + treble` in dB (SPEC §2.1.3), same for both
 * ears: low shelf 105 Hz / Q 0.707 / [gB], peaking 3 kHz / Q 0.8 / [gP], high shelf 8 kHz /
 * Q 0.707 / [gT], evaluated at fs = 48 kHz.
 */
class PreferenceCurve(val gB: Double, val gP: Double, val gT: Double) {
    fun dbAt(fHz: Double): Double =
        Rbj.lowShelfDb(fHz, BASS_FC_HZ, BASS_Q, gB) +
            Rbj.peakingDb(fHz, PRESENCE_FC_HZ, PRESENCE_Q, gP) +
            Rbj.highShelfDb(fHz, TREBLE_FC_HZ, TREBLE_Q, gT)

    /** The curve on the 1/24-octave [DspGrid]. */
    fun onGrid(): DoubleArray = DoubleArray(DspGrid.POINTS) { dbAt(DspGrid.gridHz[it]) }

    companion object {
        const val BASS_FC_HZ = 105.0
        const val BASS_Q = 0.707
        const val PRESENCE_FC_HZ = 3000.0
        const val PRESENCE_Q = 0.8
        const val TREBLE_FC_HZ = 8000.0
        const val TREBLE_Q = 0.707
    }
}
