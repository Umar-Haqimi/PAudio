package my.umarh.paudio.core.dsp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.math.pow

/** Golden set 5c (g_h, G, preamp) and focused unit tests for the §2.1.3 rules. */
class CurveGoldenTest {
    @Test
    fun curvesMatchOracle() {
        val g = Golden.load("hearing_curve")
        val tolG = g.tol("gDb")
        val tolP = g.tol("preampDb")
        val cases = g.arr("cases")
        assertThat(cases.size).isEqualTo(19)
        for (c in cases) {
            val o = c.jsonObject
            val r = CurveBuilder.build(
                o.getValue("hlLeft").doubles(), o.getValue("hlRight").doubles(), o.getValue("strength").dbl(),
                o.getValue("gB").dbl(), o.getValue("gP").dbl(), o.getValue("gT").dbl(),
            )
            val name = o.str("name")
            assertWithMessage("$name ghL").that(maxAbsDiff(r.ghLeft, o.getValue("ghLeft").doubles())).isAtMost(tolG)
            assertWithMessage("$name ghR").that(maxAbsDiff(r.ghRight, o.getValue("ghRight").doubles())).isAtMost(tolG)
            assertWithMessage("$name GL").that(maxAbsDiff(r.gLeft, o.getValue("gLeft").doubles())).isAtMost(tolG)
            assertWithMessage("$name GR").that(maxAbsDiff(r.gRight, o.getValue("gRight").doubles())).isAtMost(tolG)
            assertWithMessage("$name preamp").that(r.preampDb).isWithin(tolP).of(o.getValue("preampDb").dbl())
        }
    }

    private val zeros = DoubleArray(9)

    @Test
    fun knotsAreClampedToMinus6Plus12() {
        val hl = doubleArrayOf(-40.0, 0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 100.0)
        val hc = HearingCompensation(hl, 1.0)
        assertThat(hc.knotGainsDb[0]).isEqualTo(-6.0)
        assertThat(hc.knotGainsDb[3]).isEqualTo(12.0)
        assertThat(hc.knotGainsDb[8]).isEqualTo(12.0)
        assertThat(HearingCompensation(hl, 0.5).knotGainsDb[2]).isEqualTo(5.0)
    }

    @Test
    fun regionsBelow1kHzAndAbove14kHz() {
        val hl = DoubleArray(9) { 16.0 }
        val hc = HearingCompensation(hl, 0.5) // all knots 8 dB
        assertThat(hc.gainDb(1000.0)).isWithin(1e-9).of(8.0)
        assertThat(hc.gainDb(500.0)).isWithin(1e-9).of(4.0)      // w = 0.5 at 500 Hz
        assertThat(hc.gainDb(250.0)).isWithin(1e-9).of(0.0)
        assertThat(hc.gainDb(100.0)).isEqualTo(0.0)
        assertThat(hc.gainDb(14000.0)).isWithin(1e-9).of(8.0)
        assertThat(hc.gainDb(16000.0)).isWithin(1e-9).of(8.0)    // hold
        assertThat(hc.gainDb(15000.0)).isWithin(1e-9).of(8.0)
        assertThat(hc.gainDb(20000.0)).isWithin(1e-9).of(0.0)    // faded out
        val mid = hc.gainDb(16000.0 * 2.0.pow(0.5 * log2(20000.0 / 16000.0)))
        assertThat(mid).isWithin(1e-9).of(4.0)                    // halfway in log2 f
    }

    @Test
    fun flatHearingNoPreferenceGivesZeroCurveAndPreamp() {
        val r = CurveBuilder.build(zeros, zeros, 0.5, 0.0, 0.0, 0.0)
        assertThat(r.gLeft.all { kotlin.math.abs(it) < 1e-9 }).isTrue()
        assertThat(r.preampDb).isWithin(1e-9).of(-0.5)
    }

    @Test
    fun totalCurveIsClampedAndPreampFloored() {
        val hl = DoubleArray(9) { 100.0 }
        val r = CurveBuilder.build(hl, hl, 1.0, 12.0, 6.0, 8.0)
        assertThat(r.gLeft.max()).isAtMost(15.0)
        assertThat(r.gLeft.max()).isWithin(1e-9).of(15.0)
        assertThat(r.preampDb).isEqualTo(-15.0) // -15.5 floored
        val cut = CurveBuilder.build(zeros, zeros, 0.0, -12.0, -6.0, -8.0)
        assertThat(cut.gLeft.min()).isAtLeast(-12.0)
        assertThat(cut.preampDb).isWithin(1e-9).of(-0.5) // no positive G -> -0.5
    }

    @Test
    fun preampUsesTheLouderEar() {
        val quiet = DoubleArray(9)
        val loud = DoubleArray(9) { 8.0 }
        val r = CurveBuilder.build(quiet, loud, 1.0, 0.0, 0.0, 0.0)
        assertThat(r.preampDb).isWithin(1e-9).of(-(r.gRight.max()) - 0.5)
        assertThat(r.gLeft.max()).isWithin(1e-9).of(0.0)
    }
}
