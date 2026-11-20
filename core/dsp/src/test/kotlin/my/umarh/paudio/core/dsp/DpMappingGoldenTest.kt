package my.umarh.paudio.core.dsp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Golden set 6 (DP layout, band gains, reconstruction) — DECISIONS §1, SPEC §2.1.4. */
class DpMappingGoldenTest {
    private val g = Golden.load("dp_band_mapping")

    private fun layoutOf(o: JsonObject): DpBandLayout = when (o.str("id")) {
        "main" -> DpBandLayout.of(o.getValue("fs").dbl().toInt(), o.getValue("blockSize").dbl().toInt())
        "fb51" -> DpBandLayout.fallback51(o.getValue("fs").dbl().toInt(), o.getValue("blockSize").dbl().toInt())
        "spec40" -> DpBandLayout.spec40(o.getValue("fs").dbl().toInt(), o.getValue("blockSize").dbl().toInt())
        else -> error("layout")
    }

    @Test
    fun blockSizeAndLayoutsMatchOracleExactly() {
        for (l in g.arr("layouts")) {
            val o = l.jsonObject
            val fs = o.getValue("fs").dbl().toInt()
            val frameMs = o.getValue("frameMs").dbl().toFloat()
            assertThat(dpBlockSize(fs, frameMs)).isEqualTo(o.getValue("blockSize").dbl().toInt())
            val layout = layoutOf(o)
            assertThat(layout.stops.toList()).isEqualTo(o.getValue("stops").ints().toList())
            val cut = o.getValue("cutoffsHz").doubles()
            assertThat(layout.cutoffHz.size).isEqualTo(cut.size)
            for (i in cut.indices) assertThat(layout.cutoffHz[i]).isEqualTo(cut[i]) // exact
        }
    }

    @Test
    fun bandGainsAndReconstructionMatchOracleAndMeetAcceptance() {
        val tolGain = g.tol("bandGainDb")
        val tolRms = g.tol("reconRmsDb")
        val accept = g.tol("reconRmsAcceptDb")
        val layouts = g.arr("layouts").map { it.jsonObject }
        val curves = g.arr("curves").associate { it.jsonObject.str("name") to it.jsonObject }
        var worstMain = 0.0
        var checked = 0
        for (r in g.arr("results")) {
            val o = r.jsonObject
            val fs = o.getValue("fs").dbl().toInt()
            val lid = o.str("layout")
            val layout = layoutOf(layouts.first { it.str("id") == lid && it.getValue("fs").dbl().toInt() == fs })
            val curve = curves.getValue(o.str("curve"))
            for ((ear, key) in listOf("L" to "gLeft", "R" to "gRight")) {
                val target = curve.getValue(key).doubles()
                val gains = DpBandMapper.map(layout, target)
                val expGains = o.obj("bandGainsDb").getValue(ear).doubles()
                assertThat(gains.size).isEqualTo(expGains.size)
                for (i in gains.indices) {
                    assertWithMessage("${o.str("curve")} $lid $fs $ear band $i").that(gains[i].toDouble()).isWithin(tolGain).of(expGains[i])
                }
                val rms = DpReconstruction.rmsErrorDb(layout, gains, target)
                assertWithMessage("${o.str("curve")} $lid $fs $ear rms").that(rms).isWithin(tolRms).of(o.obj("reconRmsDb").getValue(ear).dbl())
                if (lid == "main") {
                    assertThat(rms).isAtMost(accept)
                    worstMain = maxOf(worstMain, rms)
                }
                checked++
            }
        }
        assertThat(checked).isAtLeast(2 * 3 * 22 * 2)
        println("worst 87-band DP reconstruction RMS = $worstMain dB")
    }

    @Test
    fun geometryAt48kHz() {
        assertThat(dpBlockSize(48000, 16.0f)).isEqualTo(1024)
        assertThat(dpBlockSize(44100, 16.0f)).isEqualTo(1024)
        assertThat(dpBlockSize(96000, 16.0f)).isEqualTo(2048)
        assertThat(dpBlockSize(48000, 10.0f)).isEqualTo(512)
        assertThat(dpBlockSize(48000, 0.01f)).isEqualTo(8)
        val l = DpBandLayout.of(48000, 1024)
        assertThat(l.bandCount).isEqualTo(87)
        assertThat(l.stops.first()).isEqualTo(0)
        assertThat(l.stops.last()).isEqualTo(512)
        assertThat(l.cutoffHz.last()).isEqualTo(24000.0)
        assertThat(DpBandLayout.of(48000, 512).bandCount).isEqualTo(79)
        assertThat(DpBandLayout.fallback51(48000, 1024).bandCount).isEqualTo(51)
        assertThat(DpBandLayout.spec40(48000, 1024).bandCount).isEqualTo(40)
        for (i in 1 until l.stops.size) assertThat(l.stops[i]).isGreaterThan(l.stops[i - 1])
    }

    @Test
    fun flatCurveReconstructsExactly() {
        val l = DpBandLayout.of(48000, 1024)
        val flat = DoubleArray(DspGrid.POINTS) { 3.0 }
        val gains = DpBandMapper.map(l, flat)
        assertThat(gains.all { it == 3.0f }).isTrue()
        assertThat(DpReconstruction.rmsErrorDb(l, gains, flat)).isWithin(1e-6).of(0.0)
        // above the last cutoff does not exist: response is held at both ends
        assertThat(DpReconstruction.responseDb(l, gains, 1.0)).isWithin(1e-6).of(3.0)
        assertThat(DpReconstruction.responseDb(l, gains, 24000.0)).isWithin(1e-6).of(3.0)
    }
}
