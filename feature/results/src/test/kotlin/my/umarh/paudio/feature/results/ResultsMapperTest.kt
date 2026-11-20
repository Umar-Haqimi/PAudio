package my.umarh.paudio.feature.results

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import org.junit.Test

class ResultsMapperTest {
    private val freqs = FrequencyGrid.TEST_FREQUENCIES_HZ

    @Test
    fun flatNormalSet_isSimilarWithNoNotes() {
        val c = ResultsMapper.content(test(1), thresholds(1))!!
        assertThat(c.left.pta3).isWithin(1e-3).of(0.0)
        assertThat(c.left.wording).isEqualTo(PtaWording.SIMILAR)
        assertThat(c.right.wording).isEqualTo(PtaWording.SIMILAR)
        assertThat(c.asymmetry).isFalse()
        assertThat(c.anyCensored).isFalse()
        assertThat(c.reliability.hasNote).isFalse()
        assertThat(c.frequenciesHz).isEqualTo(freqs)
        c.leftDisplay.forEach { assertThat(it).isWithin(1e-3f).of(0f) }
    }

    @Test
    fun butterflyInput_followsHlPerEarInGridOrder_andIsDisplayClamped() {
        val th = thresholds(
            1,
            right = { if (it == 14000) -25.0 else if (it == 4000) 12.0 else 0.0 },
            left = { if (it == 8000) 130.0 else 0.0 },
        )
        val c = ResultsMapper.content(test(1), th)!!
        assertThat(c.rightDisplay[freqs.indexOf(4000)]).isWithin(1e-3f).of(12f)
        assertThat(c.rightDisplay[freqs.indexOf(14000)]).isWithin(1e-3f).of(-10f) // clamped to -10
        assertThat(c.leftDisplay[freqs.indexOf(8000)]).isWithin(1e-3f).of(100f) // clamped to 100
        // The curve input keeps the unclamped value.
        assertThat(c.hlLeft[freqs.indexOf(8000)]).isWithin(1e-3).of(130.0)
        assertThat(c.hlRight[freqs.indexOf(14000)]).isWithin(1e-3).of(-25.0)
    }

    @Test
    fun censoredPoints_stayAtTheirBound_andAreFlaggedAsBeyondRange() {
        val th = thresholds(1, right = { if (it == 14000) 40.0 else 0.0 }, censoredAt = setOf(Ear.RIGHT to 14000, Ear.RIGHT to 12500))
        val c = ResultsMapper.content(test(1), th)!!
        assertThat(c.censoredRight[freqs.indexOf(14000)]).isTrue()
        assertThat(c.censoredRight[freqs.indexOf(12500)]).isTrue()
        assertThat(c.censoredLeft.none { it }).isTrue()
        assertThat(c.rightDisplay[freqs.indexOf(14000)]).isWithin(1e-3f).of(40f)
        assertThat(c.right.ehfBeyondRange).isTrue()
        assertThat(c.right.anyCensored).isTrue()
        assertThat(c.left.ehfBeyondRange).isFalse()
        assertThat(c.anyCensored).isTrue()
    }

    @Test
    fun censoredBelowEhf_isCensoredButNotEhfBeyondRange() {
        val th = thresholds(1, censoredAt = setOf(Ear.LEFT to 8000))
        val c = ResultsMapper.content(test(1), th)!!
        assertThat(c.left.anyCensored).isTrue()
        assertThat(c.left.ehfBeyondRange).isFalse()
    }

    @Test
    fun ptaWording_bandEdgesFollowTheWhoBandsButNeverSayHealthy() {
        assertThat(ResultsMapper.wording(-3.0)).isEqualTo(PtaWording.SIMILAR)
        assertThat(ResultsMapper.wording(19.9)).isEqualTo(PtaWording.SIMILAR)
        assertThat(ResultsMapper.wording(20.0)).isEqualTo(PtaWording.SLIGHTLY_HIGHER)
        assertThat(ResultsMapper.wording(34.9)).isEqualTo(PtaWording.SLIGHTLY_HIGHER)
        assertThat(ResultsMapper.wording(35.0)).isEqualTo(PtaWording.HIGHER)
        assertThat(ResultsMapper.wording(64.9)).isEqualTo(PtaWording.HIGHER)
        assertThat(ResultsMapper.wording(65.0)).isEqualTo(PtaWording.MUCH_HIGHER)
        assertThat(ResultsMapper.wording(120.0)).isEqualTo(PtaWording.MUCH_HIGHER)
    }

    @Test
    fun pta3_perEarFromOneTwoFourKilohertz() {
        // Right: 0 at 1k/2k, 9 at 4k -> PTA3 3. Left: 36 at 2k and 4k, 0 at 1k -> PTA3 24 (anchor stays on the right ear).
        val th = thresholds(
            1,
            right = { if (it == 4000) 9.0 else 0.0 },
            left = { if (it == 2000 || it == 4000) 36.0 else 0.0 },
        )
        val c = ResultsMapper.content(test(1), th)!!
        assertThat(c.right.pta3).isWithin(1e-3).of(3.0)
        assertThat(c.right.wording).isEqualTo(PtaWording.SIMILAR)
        assertThat(c.left.pta3).isWithin(1e-3).of(24.0)
        assertThat(c.left.wording).isEqualTo(PtaWording.SLIGHTLY_HIGHER)
    }

    @Test
    fun asymmetry_needsTwoAdjacentFrequencies() {
        val two = thresholds(1, left = { if (it == 4000 || it == 6000) 30.0 else 0.0 })
        assertThat(ResultsMapper.content(test(1), two)!!.asymmetry).isTrue()
        val one = thresholds(1, left = { if (it == 4000) 30.0 else 0.0 })
        assertThat(ResultsMapper.content(test(1), one)!!.asymmetry).isFalse()
    }

    @Test
    fun reliability_quickUnreliableOffersRedo_andLowConfidencePointsAreCounted() {
        val th = thresholds(1, lowConfidenceAt = setOf(Ear.LEFT to 3000, Ear.RIGHT to 6000))
        val c = ResultsMapper.content(test(1, reliable = false), th)!!
        assertThat(c.reliability.unreliable).isTrue()
        assertThat(c.reliability.offerRedo).isTrue()
        assertThat(c.reliability.lowConfidencePoints).isEqualTo(2)
        assertThat(c.reliability.hasNote).isTrue()
        val ok = ResultsMapper.content(test(1, reliable = true), thresholds(1))!!
        assertThat(ok.reliability.offerRedo).isFalse()
    }

    @Test
    fun incompleteThresholds_mapToNull() {
        assertThat(ResultsMapper.content(test(1), thresholds(1).drop(1))).isNull()
        assertThat(ResultsMapper.content(test(1), emptyList())).isNull()
    }

    @Test
    fun formatDb_truncatesTowardZeroAndNeverPrintsMinusZero() {
        val en = java.util.Locale.US
        assertThat(formatDb(19.96, en)).isEqualTo("19.9")
        assertThat(formatDb(-0.04, en)).isEqualTo("0")
        assertThat(formatDb(3.0, en)).isEqualTo("3")
        assertThat(formatDb(12.5, java.util.Locale.GERMANY)).isEqualTo("12,5")
    }
}
