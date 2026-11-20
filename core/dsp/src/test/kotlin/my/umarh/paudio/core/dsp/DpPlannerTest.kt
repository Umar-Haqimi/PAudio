package my.umarh.paudio.core.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DpPlannerTest {
    private val hl = doubleArrayOf(0.0, 0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0)
    private val zeros = DoubleArray(9)

    @Test
    fun planMatchesManualPipeline() {
        val p = DpEqPlanner.plan(hl, zeros, 0.5, 3.0, -2.0, 4.0, fs = 48000)
        val c = CurveBuilder.build(hl, zeros, 0.5, 3.0, -2.0, 4.0)
        assertThat(p.layout.bandCount).isEqualTo(87)
        assertThat(p.cutoffHz.size).isEqualTo(87)
        assertThat(p.cutoffHz.last()).isEqualTo(24000f)
        assertThat(p.preampDb).isEqualTo(c.preampDb.toFloat())
        assertThat(p.gainsLeft.toList()).isEqualTo(DpBandMapper.map(p.layout, c.gLeft).toList())
        assertThat(p.gainsLeft.toList()).isNotEqualTo(p.gainsRight.toList())
        assertThat(DpReconstruction.rmsErrorDb(p.layout, p.gainsLeft, c.gLeft)).isAtMost(0.5)
    }

    @Test
    fun flatPlanIsAllZero() {
        val p = DpEqPlanner.flat(48000)
        assertThat(p.gainsLeft.all { it == 0f } && p.gainsRight.all { it == 0f }).isTrue()
        assertThat(p.preampDb).isEqualTo(0f)
        assertThat(p.loudnessDeltaDb(LoudnessPredictor.pink())).isEqualTo(0.0)
    }

    @Test
    fun ladderStepsAndLoudness() {
        assertThat(DpEqPlanner.plan(hl, hl, 0.5, 0.0, 0.0, 0.0, 48000, ladder = DpLadderStep.FB51).layout.bandCount).isEqualTo(51)
        assertThat(DpEqPlanner.plan(hl, hl, 0.5, 0.0, 0.0, 0.0, 48000, ladder = DpLadderStep.SPEC40).layout.bandCount).isEqualTo(40)
        val p = DpEqPlanner.plan(zeros, zeros, 0.0, 6.0, 0.0, 0.0, 48000)
        assertThat(p.loudnessDeltaDb(LoudnessPredictor.pink())).isGreaterThan(0.0)
    }

    @Test
    fun layoutInvariants() {
        val l = DpBandLayout.of(48000, 1024)
        assertThat(l.degenerate).isFalse()
        assertThat(l.effectiveBandCount).isEqualTo(87)
        val s = DpBandLayout.spec40(48000, 1024)
        assertThat(s.degenerate).isTrue()
        assertThat(s.effectiveBandCount).isLessThan(40)
        assertThat(DpBandLayout.of(44100, 1024).effectiveBandCount).isEqualTo(87)
    }

    @Test
    fun inputValidation() {
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(DoubleArray(8), zeros, 0.5, 0.0, 0.0, 0.0) }
        val bad = zeros.copyOf().also { it[2] = Double.NaN }
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(bad, zeros, 0.5, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(zeros, zeros, 1.5, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(zeros, zeros, -0.1, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(zeros, zeros, 0.5, Double.POSITIVE_INFINITY, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CurveBuilder.build(zeros, zeros, 0.5, 0.0, Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { geomspace(1.0, 512.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { DpEqPlanner.flat(0) }
    }
}
