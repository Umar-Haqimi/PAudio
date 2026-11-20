package my.umarh.paudio.designsystem

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.designsystem.chart.ChartMath
import org.junit.Test

class ChartMathTest {
    @Test
    fun waveLength_isLongAtOneKhzAndShortAtFourteen() {
        assertThat(ChartMath.waveLengthDp(1000f)).isWithin(1e-3f).of(48f)
        assertThat(ChartMath.waveLengthDp(14000f)).isWithin(1e-3f).of(10f)
        assertThat(ChartMath.waveLengthDp(500f)).isWithin(1e-3f).of(48f) // clamped
        val mid = ChartMath.waveLengthDp(3742f) // sqrt(14) kHz -> halfway on the log scale
        assertThat(mid).isWithin(0.1f).of(29f)
        assertThat(ChartMath.waveLengthDp(4000f)).isLessThan(ChartMath.waveLengthDp(2000f))
    }

    @Test
    fun butterflyWidth_mapsClampedHl() {
        assertThat(ChartMath.butterflyHalfWidthFraction(60f)).isEqualTo(0f)
        assertThat(ChartMath.butterflyHalfWidthFraction(100f)).isEqualTo(0f)
        assertThat(ChartMath.butterflyHalfWidthFraction(-10f)).isEqualTo(1f)
        assertThat(ChartMath.butterflyHalfWidthFraction(-50f)).isEqualTo(1f)
        assertThat(ChartMath.butterflyHalfWidthFraction(25f)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun butterflyHeight_isLogBetweenOneAndFourteenKhz() {
        assertThat(ChartMath.butterflyHeightFraction(1000f)).isEqualTo(0f)
        assertThat(ChartMath.butterflyHeightFraction(14000f)).isWithin(1e-6f).of(1f)
        assertThat(ChartMath.butterflyHeightFraction(3742f)).isWithin(1e-3f).of(0.5f)
    }

    @Test
    fun audiogramTransforms() {
        assertThat(ChartMath.audiogramXFraction(1000f)).isEqualTo(0f)
        assertThat(ChartMath.audiogramXFraction(16000f)).isWithin(1e-6f).of(1f)
        assertThat(ChartMath.audiogramXFraction(4000f)).isWithin(1e-6f).of(0.5f)
        // y increases downward: -10 dB at the top, 100 dB at the bottom.
        assertThat(ChartMath.audiogramYFraction(-10f)).isEqualTo(0f)
        assertThat(ChartMath.audiogramYFraction(100f)).isEqualTo(1f)
        assertThat(ChartMath.audiogramYFraction(45f)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun eqTransforms() {
        assertThat(ChartMath.eqXFraction(20.0)).isWithin(1e-6f).of(0f)
        assertThat(ChartMath.eqXFraction(20000.0)).isWithin(1e-6f).of(1f)
        assertThat(ChartMath.eqYFraction(15.0)).isEqualTo(0f)
        assertThat(ChartMath.eqYFraction(-15.0)).isEqualTo(1f)
        assertThat(ChartMath.eqYFraction(0.0)).isEqualTo(0.5f)
    }
}

class ProfileSeedColorTest {
    private val freqs = listOf(1000, 2000, 4000, 8000, 14000)

    @Test
    fun hue_followsFrequencyOfLargestHl() {
        assertThat(ProfileSeedColor.hueFor(1000)).isWithin(1e-3f).of(200f)
        assertThat(ProfileSeedColor.hueFor(14000)).isWithin(1e-3f).of(340f)
        val low = ProfileSeedColor.from(listOf(30f, 0f, 0f, 0f, 0f), listOf(30f, 0f, 0f, 0f, 0f), freqs)
        val high = ProfileSeedColor.from(listOf(0f, 0f, 0f, 0f, 40f), listOf(0f, 0f, 0f, 0f, 40f), freqs)
        assertThat(low).isNotEqualTo(high)
    }

    @Test
    fun saturation_risesWithAsymmetry() {
        assertThat(ProfileSeedColor.saturationFor(0f)).isWithin(1e-6f).of(0.35f)
        assertThat(ProfileSeedColor.saturationFor(30f)).isWithin(1e-6f).of(0.85f)
        assertThat(ProfileSeedColor.saturationFor(100f)).isWithin(1e-6f).of(0.85f)
        assertThat(ProfileSeedColor.saturationFor(15f)).isWithin(1e-6f).of(0.6f)
    }

    @Test
    fun deterministic_andFallbackWhenNoData() {
        val a = ProfileSeedColor.from(listOf(1f, 2f, null, 4f, 5f), listOf(5f, 4f, 3f, null, 1f), freqs)
        val b = ProfileSeedColor.from(listOf(1f, 2f, null, 4f, 5f), listOf(5f, 4f, 3f, null, 1f), freqs)
        assertThat(a.toArgb()).isEqualTo(b.toArgb())
        assertThat(ProfileSeedColor.from(emptyList(), emptyList(), freqs)).isEqualTo(ProfileSeedColor.FALLBACK)
        assertThat(ProfileSeedColor.from(listOf(null, null), listOf(null, null), listOf(1000, 2000)))
            .isEqualTo(ProfileSeedColor.FALLBACK)
    }

    @Test
    fun asymmetricProfile_isMoreSaturatedThanSymmetric() {
        val sym = ProfileSeedColor.from(listOf(10f, 10f, 10f, 10f, 10f), listOf(10f, 10f, 10f, 10f, 10f), freqs)
        val asym = ProfileSeedColor.from(listOf(10f, 10f, 10f, 10f, 10f), listOf(40f, 40f, 40f, 40f, 40f), freqs)
        fun sat(c: androidx.compose.ui.graphics.Color): Float {
            val mx = maxOf(c.red, c.green, c.blue); val mn = minOf(c.red, c.green, c.blue)
            return if (mx == 0f) 0f else (mx - mn) / mx
        }
        assertThat(sat(asym)).isGreaterThan(sat(sym))
    }
}
