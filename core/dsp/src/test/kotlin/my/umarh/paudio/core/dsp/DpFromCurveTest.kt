package my.umarh.paudio.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpFromCurveTest {
    @Test fun matchesLayoutAndMapperAndKeepsPreamp() {
        val g = DoubleArray(DspGrid.POINTS) { 3.0 }
        for (fs in intArrayOf(44_100, 48_000)) {
            val plan = DpEqPlanner.fromCurve(g, g, -3.5f, fs)
            val layout = DpBandLayout.of(fs, dpBlockSize(fs, DP_FRAME_MS))
            assertEquals(layout.bandCount, plan.cutoffHz.size)
            assertArrayEquals(DpBandMapper.map(layout, g), plan.gainsLeft, 0f)
            assertEquals(-3.5f, plan.preampDb, 0f)
        }
    }

    @Test fun ladderIsHonoured() {
        val g = DoubleArray(DspGrid.POINTS)
        val main = DpEqPlanner.fromCurve(g, g, 0f, 48_000)
        val fb = DpEqPlanner.fromCurve(g, g, 0f, 48_000, ladder = DpLadderStep.FB51)
        assertTrue(fb.cutoffHz.size < main.cutoffHz.size)
    }
}
