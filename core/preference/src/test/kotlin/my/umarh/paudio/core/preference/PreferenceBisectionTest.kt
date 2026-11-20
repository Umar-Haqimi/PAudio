package my.umarh.paudio.core.preference

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.random.Random

class PreferenceBisectionTest {

    private fun golden(): JsonObject {
        val text = checkNotNull(javaClass.getResource("/golden/preference.json")).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Answers "+" (higher gain) / "-" (lower gain) / "?" regardless of which side it landed on. */
    private fun PreferenceBisection.answer(ch: String) {
        val r = checkNotNull(currentRound)
        when (ch) {
            "?" -> cantTell()
            "+" -> choose(r.plusSide)
            "-" -> choose(if (r.plusSide == AbSide.A) AbSide.B else AbSide.A)
            else -> error(ch)
        }
    }

    @Test
    fun goldenSequencesMatchOracleForSeveralSeeds() {
        val cases = golden()["cases"]!!.jsonArray
        assertThat(cases.size).isAtLeast(8)
        for (seed in 0..5) {
            for (case in cases) {
                val o = case.jsonObject
                val b = PreferenceBisection(Random(seed))
                val expectedRounds = o["rounds"]!!.jsonArray
                var n = 0
                for (c in o["choices"]!!.jsonArray) {
                    val r = checkNotNull(b.currentRound) { "finished early in ${o["name"]}" }
                    val er = expectedRounds[n++].jsonObject
                    assertThat(r.axis.name).isEqualTo(er["axis"]!!.jsonPrimitive.content)
                    assertThat(minOf(r.aGain, r.bGain)).isEqualTo(er["low"]!!.jsonPrimitive.double)
                    assertThat(maxOf(r.aGain, r.bGain)).isEqualTo(er["high"]!!.jsonPrimitive.double)
                    b.answer(c.jsonPrimitive.content)
                }
                assertThat(b.isFinished).isTrue()
                val out = b.outcome
                assertThat(out.gB).isEqualTo(o["gB"]!!.jsonPrimitive.double)
                assertThat(out.gP).isEqualTo(o["gP"]!!.jsonPrimitive.double)
                assertThat(out.gT).isEqualTo(o["gT"]!!.jsonPrimitive.double)
                assertThat(out.rounds).hasSize(expectedRounds.size)
            }
        }
    }

    @Test
    fun axisInterleavingIsBptBptBpt() {
        val b = PreferenceBisection(Random(1))
        val axes = mutableListOf<PreferenceAxis>()
        while (!b.isFinished) { axes += b.currentRound!!.axis; b.answer("+") }
        assertThat(axes).containsExactly(
            PreferenceAxis.BASS, PreferenceAxis.PRESENCE, PreferenceAxis.TREBLE,
            PreferenceAxis.BASS, PreferenceAxis.PRESENCE, PreferenceAxis.TREBLE,
            PreferenceAxis.BASS, PreferenceAxis.PRESENCE, PreferenceAxis.TREBLE,
        ).inOrder()
    }

    @Test
    fun sideRandomizationIsMappedCorrectlyAndUsesBothSides() {
        val plusSides = mutableSetOf<AbSide>()
        for (seed in 0..20) {
            val b = PreferenceBisection(Random(seed))
            val r = b.currentRound!!
            plusSides += r.plusSide
            val plusGain = if (r.plusSide == AbSide.A) r.aGain else r.bGain
            val minusGain = if (r.plusSide == AbSide.A) r.bGain else r.aGain
            assertThat(plusGain).isEqualTo(6.0)
            assertThat(minusGain).isEqualTo(-6.0)
            // Picking side A or B sets the centre to that side's gain.
            b.choose(AbSide.A)
            assertThat(b.outcome.gB).isEqualTo(r.aGain)
            val b2 = PreferenceBisection(Random(seed))
            b2.choose(AbSide.B)
            assertThat(b2.outcome.gB).isEqualTo(r.bGain)
        }
        assertThat(plusSides).containsExactly(AbSide.A, AbSide.B)
    }

    @Test
    fun sameSeedIsDeterministic() {
        fun run(): String {
            val b = PreferenceBisection(Random(99))
            while (!b.isFinished) b.choose(AbSide.A)
            return b.outcome.roundsJson()
        }
        assertThat(run()).isEqualTo(run())
    }

    @Test
    fun clampedComparisonUsesClampedValue() {
        // Presence all-plus: round 3 compares 5 vs clamp(7)=6.
        val b = PreferenceBisection(Random(3))
        repeat(7) { b.answer("+") } // B P T B P T B
        val r = b.currentRound!!
        assertThat(r.axis).isEqualTo(PreferenceAxis.PRESENCE)
        assertThat(minOf(r.aGain, r.bGain)).isEqualTo(5.0)
        assertThat(maxOf(r.aGain, r.bGain)).isEqualTo(6.0)
    }

    @Test
    fun cantTellSkipsRemainingRoundsOfThatAxisAndKeepsCentre() {
        val b = PreferenceBisection(Random(4))
        b.answer("+")   // B -> 6
        b.answer("?")   // P can't tell
        b.answer("+")   // T -> 5
        b.answer("+")   // B -> 9
        // next is T (P skipped)
        assertThat(b.currentRound!!.axis).isEqualTo(PreferenceAxis.TREBLE)
        b.answer("?")
        b.answer("+")   // B -> 10.5
        assertThat(b.isFinished).isTrue()
        assertThat(b.outcome.gP).isEqualTo(0.0)
        assertThat(b.outcome.gB).isEqualTo(10.5)
        assertThat(b.outcome.gT).isEqualTo(5.0)
        assertThat(b.roundsCompleted).isEqualTo(6)
    }

    @Test
    fun roundsJsonIsSerializableAndComplete() {
        val b = PreferenceBisection(Random(5))
        b.answer("+"); b.answer("-"); b.cantTell()
        val json = b.outcome.roundsJson()
        val arr = Json.parseToJsonElement(json).jsonArray
        assertThat(arr).hasSize(3)
        val first = arr[0].jsonObject
        assertThat(first.keys).containsAtLeast("round", "axis", "aGain", "bGain", "choice", "plusSide")
        assertThat(first["axis"]).isEqualTo(JsonPrimitive("BASS"))
        assertThat(arr[2].jsonObject["choice"]).isEqualTo(JsonPrimitive("CANT_TELL"))
    }
}
