package my.umarh.paudio.feature.health

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.audiometry.WhoGrade
import my.umarh.paudio.core.model.Ear
import org.junit.Test

class HealthMapperTest {
    @Test
    fun normalTest_pta3AndGrade() {
        val c = HealthMapper.content(test(1), thresholds(1), emptyList())!!
        assertThat(c.right.pta3).isWithin(0.01).of(0.0)
        assertThat(c.left.pta3).isWithin(0.01).of(0.0)
        assertThat(c.grade).isEqualTo(WhoGrade.NORMAL)
        assertThat(c.asymmetry).isFalse()
        assertThat(c.reliability.hasNote).isFalse()
        assertThat(c.showTrend).isFalse()
    }

    @Test
    fun censoredEhf_isFlaggedAndPlottedAtBoundWithArrow() {
        val censored = setOf(Ear.RIGHT to 12500, Ear.RIGHT to 14000)
        val th = thresholds(1, right = { if (it >= 10000) 45.0 else 0.0 }, censoredAt = censored)
        val c = HealthMapper.content(test(1), th, emptyList())!!
        assertThat(c.anyCensored).isTrue()
        assertThat(c.right.ehfBeyondRange).isTrue()
        assertThat(c.left.ehfBeyondRange).isFalse()
        assertThat(c.right.ehf).isWithin(0.01).of(45.0)
        assertThat(c.rightPoints.first { it.frequencyHz == 14000 }.censored).isTrue()
        assertThat(c.rightPoints.first { it.frequencyHz == 14000 }.hlDb).isWithin(0.01f).of(45f)
        assertThat(c.leftPoints.none { it.censored }).isTrue()
    }

    @Test
    fun displayValuesAreClampedButSummariesAreNot() {
        val th = thresholds(1, right = { if (it >= 10000) 140.0 else 0.0 })
        val c = HealthMapper.content(test(1), th, emptyList())!!
        assertThat(c.rightPoints.last().hlDb).isEqualTo(100f)
        assertThat(c.right.ehf).isWithin(0.01).of(140.0)
    }

    @Test
    fun asymmetricTest_setsFlag() {
        val th = thresholds(1, left = { if (it in 4000..6000) 20.0 else 0.0 })
        assertThat(HealthMapper.content(test(1), th, emptyList())!!.asymmetry).isTrue()
    }

    @Test
    fun singleFrequencyDifference_doesNotSetFlag() {
        val th = thresholds(1, left = { if (it == 4000) 20.0 else 0.0 })
        assertThat(HealthMapper.content(test(1), th, emptyList())!!.asymmetry).isFalse()
    }

    @Test
    fun unreliableTest_andLowConfidencePoints() {
        val th = thresholds(1, lowConfidenceAt = setOf(Ear.LEFT to 3000, Ear.RIGHT to 6000))
        val c = HealthMapper.content(test(1, reliable = false), th, emptyList())!!
        assertThat(c.reliability.unreliable).isTrue()
        assertThat(c.reliability.lowConfidencePoints).isEqualTo(2)
        assertThat(c.reliability.hasNote).isTrue()
        assertThat(c.leftPoints.first { it.frequencyHz == 3000 }.lowConfidence).isTrue()
        assertThat(c.rightPoints.first { it.frequencyHz == 3000 }.lowConfidence).isFalse()
    }

    @Test
    fun incompleteThresholds_mapToNull() {
        assertThat(HealthMapper.content(test(1), thresholds(1).drop(1), emptyList())).isNull()
        val nullLevel = thresholds(1).map { if (it.freqHz == 1000) it.copy(levelDbfs = null) else it }
        assertThat(HealthMapper.measure(nullLevel)).isNull()
    }

    @Test
    fun trendPoint_recomputedPerTestFromRawThresholds() {
        val a = HealthMapper.trendPoint(test(1), thresholds(1))!!
        val b = HealthMapper.trendPoint(test(2), thresholds(2, right = { if (it == 4000) 30.0 else 0.0 }))!!
        assertThat(a.rightPta).isWithin(0.01f).of(0f)
        assertThat(b.rightPta).isWithin(0.01f).of(10f)
        assertThat(b.leftPta).isWithin(0.01f).of(0f)
        assertThat(b.dateMillis).isEqualTo(test(2).startedAt)
    }

    @Test
    fun trendShownOnlyWithTwoOrMorePoints() {
        val p = HealthMapper.trendPoint(test(1), thresholds(1))!!
        val one = HealthMapper.content(test(1), thresholds(1), listOf(p))!!
        val two = HealthMapper.content(test(1), thresholds(1), listOf(p, p.copy(dateMillis = p.dateMillis + 1)))!!
        assertThat(one.showTrend).isFalse()
        assertThat(two.showTrend).isTrue()
    }
}
