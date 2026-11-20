package my.umarh.paudio.core.audiometry

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import my.umarh.paudio.core.model.Ear
import org.junit.Test
import kotlin.math.abs

/** Golden sets 5a (measurement model) and 8 (Hughson-Westlake scripts). */
class GoldenModelTests {
    @Test
    fun measurementModelSet5a() {
        val g = Golden.load("measurement_model")
        val tol = g.getValue("tolerance").jsonObject.getValue("dB").dbl()
        assertThat(g.cases().size).isEqualTo(13)
        for (c in g.cases()) {
            val o = c.jsonObject
            val name = o.getValue("name").str()
            val th = o.getValue("thresholds").jsonObject
            val input = Ear.entries.associateWith { ear ->
                th.getValue(ear.name).jsonArray.map {
                    val p = it.jsonObject
                    ThresholdPoint(p.getValue("freqHz").int(), p.getValue("levelDbfs").dbl(), p.getValue("censored").bool())
                }
            }
            val r = MeasurementModel.compute(input)
            val e = o.getValue("expected").jsonObject
            fun near(a: Double, b: Double, what: String) = assertThat(abs(a - b)).isAtMost(tol).also { if (abs(a - b) > tol) println("$name $what: $a vs $b") }
            near(r.anchor, e.getValue("A").dbl(), "A")
            for (ear in Ear.entries) {
                val n = e.getValue("N").jsonObject.getValue(ear.name).doubles()
                val hl = e.getValue("HL").jsonObject.getValue(ear.name).doubles()
                for (i in n.indices) {
                    near(r.normalized.getValue(ear)[i], n[i], "N[$ear][$i]")
                    near(r.hlEst.getValue(ear)[i], hl[i], "HL[$ear][$i]")
                }
                near(r.pta3.getValue(ear), e.getValue("pta3").jsonObject.getValue(ear.name).dbl(), "pta3")
                near(r.ehf.getValue(ear), e.getValue("ehf").jsonObject.getValue(ear.name).dbl(), "ehf")
            }
            near(r.betterEarPta3, e.getValue("betterEarPta3").dbl(), "better")
            assertThat(r.grade.name).isEqualTo(e.getValue("grade").str())
            assertThat(r.asymmetry).isEqualTo(e.getValue("asymmetry").bool())
        }
    }

    @Test
    fun whoGradeBands() {
        val cases = listOf(
            -5.0 to WhoGrade.NORMAL, 19.999 to WhoGrade.NORMAL, 20.0 to WhoGrade.MILD, 34.999 to WhoGrade.MILD,
            35.0 to WhoGrade.MODERATE, 49.9 to WhoGrade.MODERATE, 50.0 to WhoGrade.MODERATELY_SEVERE,
            64.9 to WhoGrade.MODERATELY_SEVERE, 65.0 to WhoGrade.SEVERE, 79.9 to WhoGrade.SEVERE,
            80.0 to WhoGrade.PROFOUND, 94.9 to WhoGrade.PROFOUND, 95.0 to WhoGrade.COMPLETE, 120.0 to WhoGrade.COMPLETE,
        )
        for ((pta, grade) in cases) assertThat(MeasurementModel.whoGrade(pta)).isEqualTo(grade)
        assertThat(MeasurementModel.displayHl(-30.0)).isEqualTo(-10.0)
        assertThat(MeasurementModel.displayHl(150.0)).isEqualTo(100.0)
        assertThat(MeasurementModel.displayHl(12.0)).isEqualTo(12.0)
    }

    @Test
    fun hughsonWestlakeSet8() {
        val g = Golden.load("hughson_westlake")
        assertThat(g.cases().size).isEqualTo(8)
        for (c in g.cases()) {
            val o = c.jsonObject
            val name = o.getValue("name").str()
            val other = o.getValue("otherEarThresholdDb").let { if (it.isNull()) null else it.dbl() }
            val sc = HughsonWestlakeStaircase(
                o.getValue("Lexp").dbl(), o.getValue("Lmax").dbl(), o.getValue("Lmin").dbl(), other,
            )
            val responses = o.getValue("responses").str()
            val levels = o.getValue("levels").doubles()
            assertThat(responses.length).isEqualTo(levels.size)
            for ((i, ch) in responses.withIndex()) {
                assertThat(sc.isFinished).isFalse()
                assertThat(sc.currentLevel).isEqualTo(levels[i])
                sc.submit(ch == 'Y')
            }
            assertThat(sc.isFinished).isTrue()
            assertThat(sc.levels).isEqualTo(levels)
            assertThat(sc.reversals).isEqualTo(o.getValue("reversals").doubles())
            val r = checkNotNull(sc.result)
            assertThat(r.levelDbfs).isEqualTo(o.getValue("thresholdDb").dbl())
            assertThat(r.censored).isEqualTo(o.getValue("censored").bool())
            assertThat(r.lowConfidence).isEqualTo(o.getValue("lowConfidence").bool())
            assertThat(r.atFloor).isEqualTo(o.getValue("atFloor").bool())
            assertThat(r.trials).isEqualTo(o.getValue("trials").int())
            println("set8 $name ok")
        }
    }
}
