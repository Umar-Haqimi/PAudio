package my.umarh.paudio.core.dsp

/**
 * ITU-R BS.1770 K-weighting magnitude (SPEC §2.1.5): pre-filter (high-shelf) cascaded with the RLB
 * high-pass, Annex 1 coefficients for fs = 48 kHz. The −0.691 dB loudness constant is not applied.
 */
object KWeighting {
    const val FS: Double = 48_000.0

    private const val PRE_B0 = 1.53512485958697
    private const val PRE_B1 = -2.69169618940638
    private const val PRE_B2 = 1.19839281085285
    private const val PRE_A1 = -1.69065929318241
    private const val PRE_A2 = 0.73248077421585

    private const val RLB_A1 = -1.99004745483398
    private const val RLB_A2 = 0.99007225036621

    /** `20·log10 |H_pre(e^jω)·H_rlb(e^jω)|` at [fHz]. */
    fun magnitudeDb(fHz: Double): Double =
        biquadMagnitudeDb(PRE_B0, PRE_B1, PRE_B2, 1.0, PRE_A1, PRE_A2, fHz, FS) +
            biquadMagnitudeDb(1.0, -2.0, 1.0, 1.0, RLB_A1, RLB_A2, fHz, FS)

    /** `|K(f)|²` (power gain, linear). */
    fun powerGain(fHz: Double): Double = Math.pow(10.0, magnitudeDb(fHz) / 10.0)
}
