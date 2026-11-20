package my.umarh.paudio.core.dsp

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Golden set 4 (loudness predictor) plus LTAS loading — SPEC §2.1.5, §7.1. */
class LoudnessGoldenTest {
    @Test
    fun predictorMatchesOracle() {
        val g = Golden.load("loudness_predictor")
        val tol = g.tol("deltaLDb")
        var ltasCases = 0
        assertThat(g.arr("cases").size).isEqualTo(33)
        for (c in g.arr("cases")) {
            val o = c.jsonObject
            val predictor = o["ltas"]?.let {
                ltasCases++
                LoudnessPredictor.fromLtas(Ltas.fromJson(it.jsonObject))
            } ?: LoudnessPredictor.pink()
            val got = predictor.deltaLDb(o.getValue("gLeft").doubles(), o.getValue("gRight").doubles())
            assertThat(got).isWithin(tol).of(o.getValue("expectedDb").dbl())
        }
        assertThat(ltasCases).isEqualTo(4 + 18)
    }

    @Test
    fun flatAndBroadbandAnchors() {
        val flat0 = DoubleArray(DspGrid.POINTS)
        val flat6 = DoubleArray(DspGrid.POINTS) { 6.0 }
        val p = LoudnessPredictor.pink()
        assertThat(p.deltaLDb(flat0, flat0)).isWithin(1e-9).of(0.0)
        assertThat(p.deltaLDb(flat6, flat6)).isWithin(0.005).of(6.0)
    }

    @Test
    fun ltasJsonLoaderAcceptsProducerFormatAndNormalises() {
        val centers = ThirdOctaveBands.centersHz.joinToString(",")
        val power = (1..30).joinToString(",") { "$it.0" }
        val l = Ltas.parse("""{"clip":"discipline","sampleRate":48000,"bandCentersHz":[$centers],"bandPower":[$power]}""")
        assertThat(l.clip).isEqualTo("discipline")
        assertThat(l.bandPower.sum()).isWithin(1e-12).of(1.0)
        assertThat(l.bandPower[29] / l.bandPower[0]).isWithin(1e-9).of(30.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ltasWithWrongBandCountIsRejected() {
        Ltas.parse("""{"clip":"x","sampleRate":48000,"bandCentersHz":[1000.0],"bandPower":[1.0]}""")
    }
}
