package my.umarh.paudio.core.dsp

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Golden sets 1 (RBJ), 2 (K-weighting) and 3 (PCHIP) — SPEC §7.1. */
class FilterGoldenTest {
    @Test
    fun rbjMagnitudesMatchScipyFreqz() {
        val g = Golden.load("rbj_magnitudes")
        val tol = g.tol("magDb")
        val grid = g.getValue("gridHz").doubles()
        assertThat(maxAbsDiff(grid, DspGrid.gridHz)).isLessThan(1e-9)
        val cases = g.arr("cases")
        assertThat(cases.size).isEqualTo(27)
        for (c in cases) {
            val o = c.jsonObject
            val fc = o.getValue("fcHz").dbl()
            val q = o.getValue("q").dbl()
            val gain = o.getValue("gainDb").dbl()
            val expected = o.getValue("magDb").doubles()
            val got = DoubleArray(grid.size) {
                when (o.str("type")) {
                    "lowshelf" -> Rbj.lowShelfDb(grid[it], fc, q, gain)
                    "peaking" -> Rbj.peakingDb(grid[it], fc, q, gain)
                    "highshelf" -> Rbj.highShelfDb(grid[it], fc, q, gain)
                    else -> error("type")
                }
            }
            assertThat(maxAbsDiff(got, expected)).isAtMost(tol)
        }
    }

    @Test
    fun preferenceCurveMatchesSumOfFilters() {
        val g = Golden.load("rbj_magnitudes")
        val tol = g.tol("magDb")
        assertThat(g.arr("preferenceCases").size).isEqualTo(7)
        for (c in g.arr("preferenceCases")) {
            val o = c.jsonObject
            val curve = PreferenceCurve(o.getValue("gB").dbl(), o.getValue("gP").dbl(), o.getValue("gT").dbl())
            assertThat(maxAbsDiff(curve.onGrid(), o.getValue("totalDb").doubles())).isAtMost(tol)
        }
    }

    @Test
    fun kWeightingMatchesScipyFreqz() {
        val g = Golden.load("k_weighting")
        val tol = g.tol("kDb")
        assertThat(g.arr("cases").size).isEqualTo(30)
        assertThat(g.getValue("gridHz").doubles().size).isEqualTo(240)
        for (c in g.arr("cases")) {
            val o = c.jsonObject
            val f = o.getValue("fcHz").dbl()
            assertThat(f).isWithin(1e-9).of(ThirdOctaveBands.centerHz(o.getValue("b").dbl().toInt() + 16))
            assertThat(KWeighting.magnitudeDb(f)).isWithin(tol).of(o.getValue("kDb").dbl())
        }
        val grid = g.getValue("gridHz").doubles()
        val expected = g.getValue("gridKDb").doubles()
        val got = DoubleArray(grid.size) { KWeighting.magnitudeDb(grid[it]) }
        assertThat(maxAbsDiff(got, expected)).isAtMost(tol)
    }

    @Test
    fun kWeightingShapeSanity() {
        // RLB high-pass attenuates the very low end, the pre-filter adds ~ +4 dB above 2 kHz, ~0 dB at 1 kHz.
        assertThat(KWeighting.magnitudeDb(20.0)).isLessThan(-10.0)
        assertThat(KWeighting.magnitudeDb(1000.0)).isWithin(0.1).of(0.69)
        assertThat(KWeighting.magnitudeDb(10000.0)).isWithin(0.3).of(4.0)
    }

    @Test
    fun pchipMatchesScipy() {
        val g = Golden.load("pchip")
        val tol = g.tol("valueDb")
        val knotTol = g.tol("knotExact")
        assertThat(g.arr("cases").size).isEqualTo(10)
        for (c in g.arr("cases")) {
            val o = c.jsonObject
            val knots = o.getValue("knotsHz").doubles()
            val values = o.getValue("values").doubles()
            val xs = DoubleArray(knots.size) { log2(knots[it]) }
            val p = Pchip(xs, values)
            val evalHz = o.getValue("evalHz").doubles()
            val got = DoubleArray(evalHz.size) { p.eval(log2(evalHz[it])) }
            assertThat(maxAbsDiff(got, o.getValue("expected").doubles())).isAtMost(tol)
            for (i in knots.indices) assertThat(p.eval(xs[i])).isWithin(knotTol).of(values[i])
        }
    }
}
