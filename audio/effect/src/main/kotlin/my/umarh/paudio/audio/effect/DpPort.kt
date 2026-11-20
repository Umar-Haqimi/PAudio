package my.umarh.paudio.audio.effect

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import my.umarh.paudio.core.dsp.DP_FRAME_MS
import my.umarh.paudio.core.model.EqConfig

/** Band top edge + gain as read back from / written to the effect. */
internal data class DpBand(val cutoffHz: Float, val gainDb: Float)

/**
 * Thin seam over the platform [DynamicsProcessing] so the diff/threading/readback logic in [DpEffectHandle]
 * is unit-testable on the JVM. Every method may throw `UnsupportedOperationException` (control lost) or
 * `IllegalStateException` (released), exactly like the platform class.
 */
internal interface DpPort {
    fun setEnabled(on: Boolean)
    fun hasControl(): Boolean
    fun setInputGain(channel: Int, db: Float)
    fun setPreEqBand(channel: Int, index: Int, band: DpBand)
    fun getPreEqBand(channel: Int, index: Int): DpBand
    fun setLimiterEnabled(channel: Int, enabled: Boolean)
    fun setControlListener(listener: (Boolean) -> Unit)
    fun release()
}

internal fun interface DpPortFactory {
    /** Builds a DISABLED effect with [cfg]; [mbcPassthrough] allocates one disabled MBC band (creation fallback). */
    fun create(priority: Int, session: Int, cfg: EqConfig, mbcPassthrough: Boolean): DpPort
}

/** DECISIONS §2: the limiter works on frame RMS, so -3 dB (a full-scale sine is -3.01 dB). */
const val LIMITER_THRESHOLD_DB = -3f

internal object PlatformDpPortFactory : DpPortFactory {
    override fun create(priority: Int, session: Int, cfg: EqConfig, mbcPassthrough: Boolean): DpPort {
        val dp = DynamicsProcessing(priority, session, buildConfig(cfg, mbcPassthrough))
        return PlatformDpPort(dp)
    }

    /** DECISIONS §1/§2: frequency variant, 16 ms frames, PreEQ + limiter, MBC off (or 1 passthrough band), PostEQ off. */
    private fun buildConfig(cfg: EqConfig, mbcPassthrough: Boolean): DynamicsProcessing.Config {
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            EqConfig.CHANNELS,
            true, cfg.bandCount,
            mbcPassthrough, if (mbcPassthrough) 1 else 0,
            false, 0,
            true,
        ).setPreferredFrameDuration(DP_FRAME_MS)
        for (ch in 0 until EqConfig.CHANNELS) {
            builder.setInputGainByChannelIndex(ch, cfg.preampDb)
            val eq = DynamicsProcessing.Eq(true, true, cfg.bandCount)
            val gains = cfg.channelGainsDb[ch]
            for (k in 0 until cfg.bandCount) {
                eq.setBand(k, DynamicsProcessing.EqBand(true, cfg.bandCutoffsHz[k], gains[k]))
            }
            builder.setPreEqByChannelIndex(ch, eq)
            builder.setLimiterByChannelIndex(ch, limiter(cfg.limiterEnabled))
            if (mbcPassthrough) {
                val mbc = DynamicsProcessing.Mbc(true, false, 1)
                mbc.setBand(0, DynamicsProcessing.MbcBand(false, 20_000f, 1f, 50f, 1f, 0f, 0f, -90f, 1f, 0f, 0f))
                builder.setMbcByChannelIndex(ch, mbc)
            }
        }
        return builder.build()
    }
}

private fun limiter(enabled: Boolean) =
    DynamicsProcessing.Limiter(true, enabled, 0, 1f, 50f, 10f, LIMITER_THRESHOLD_DB, 0f)

internal class PlatformDpPort(private val dp: DynamicsProcessing) : DpPort {
    override fun setEnabled(on: Boolean) {
        dp.setEnabled(on)
    }

    override fun hasControl(): Boolean = dp.hasControl()

    override fun setInputGain(channel: Int, db: Float) {
        dp.setInputGainbyChannel(channel, db)
    }

    override fun setPreEqBand(channel: Int, index: Int, band: DpBand) {
        dp.setPreEqBandByChannelIndex(channel, index, DynamicsProcessing.EqBand(true, band.cutoffHz, band.gainDb))
    }

    override fun getPreEqBand(channel: Int, index: Int): DpBand {
        val b = dp.getPreEqBandByChannelIndex(channel, index)
        return DpBand(b.cutoffFrequency, b.gain)
    }

    override fun setLimiterEnabled(channel: Int, enabled: Boolean) {
        dp.setLimiterByChannelIndex(channel, limiter(enabled))
    }

    override fun setControlListener(listener: (Boolean) -> Unit) {
        dp.setControlStatusListener(AudioEffect.OnControlStatusChangeListener { _, granted -> listener(granted) })
    }

    override fun release() {
        dp.release()
    }
}
