package my.umarh.paudio.core.model

import kotlin.math.pow

/**
 * Backend-neutral description of what the effect should do (SPEC §2.1.3/§2.1.4).
 *
 * @property preampDb input gain, applied identically to both channels (≤ 0 in practice).
 * @property bandCutoffsHz the TOP edge of each band in Hz, ascending. Band k covers (cutoff[k-1], cutoff[k]],
 *   band 0 starts at 0 Hz (matches `DynamicsProcessing.EqBand.cutoffFrequency` semantics, SPEC trap 7).
 * @property channelGainsDb per-channel band gains in dB, indexed by [Ear.channelIndex]; each list has the
 *   same size as [bandCutoffsHz].
 * @property limiterEnabled the limiter should always be on in the engine (SPEC §6); false only for tests.
 */
data class EqConfig(
    val preampDb: Float,
    val bandCutoffsHz: List<Float>,
    val channelGainsDb: List<List<Float>>,
    val limiterEnabled: Boolean = true,
) {
    init {
        require(channelGainsDb.size == CHANNELS) { "expected $CHANNELS channels, got ${channelGainsDb.size}" }
        channelGainsDb.forEach {
            require(it.size == bandCutoffsHz.size) { "gain list size ${it.size} != band count ${bandCutoffsHz.size}" }
        }
    }

    val bandCount: Int get() = bandCutoffsHz.size

    fun gainsDb(ear: Ear): List<Float> = channelGainsDb[ear.channelIndex]

    companion object {
        const val CHANNELS = 2
        const val DEFAULT_BAND_COUNT = 40

        /**
         * Default band layout of SPEC §2.1.4: `c_k = 20·2^((k+1)/4)` Hz for k = 0..38 and
         * `c_39 = sampleRate/2`. `:core:dsp` owns the real mapper and may adjust this after M0.
         */
        fun defaultBandCutoffsHz(sampleRate: Int = 48_000, bandCount: Int = DEFAULT_BAND_COUNT): List<Float> =
            List(bandCount) { k ->
                if (k == bandCount - 1) sampleRate / 2f else (20.0 * 2.0.pow((k + 1) / 4.0)).toFloat()
            }

        /** A flat (0 dB everywhere, no preamp) configuration. */
        fun flat(sampleRate: Int = 48_000, bandCount: Int = DEFAULT_BAND_COUNT): EqConfig {
            val cutoffs = defaultBandCutoffsHz(sampleRate, bandCount)
            val zeros = List(cutoffs.size) { 0f }
            return EqConfig(0f, cutoffs, listOf(zeros, zeros))
        }
    }
}
