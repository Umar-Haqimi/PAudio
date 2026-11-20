package my.umarh.paudio.audio.playback

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.dsp.DspGrid
import my.umarh.paudio.core.dsp.LoudnessPredictor
import my.umarh.paudio.core.model.Ear
import org.junit.Test

class AbGainPlannerTest {
    private val predictor = LoudnessPredictor.pink()

    private fun flatCurve(db: Double) = DoubleArray(DspGrid.POINTS) { db }

    @Test fun flatVsFlatHasEqualGains() {
        val plan = AbGainPlanner.plan(listOf(AbCurve.Flat, AbCurve.Flat), predictor)
        assertThat(plan.commonPreampDb).isWithin(1e-6f).of(-0.5f)
        assertThat(plan.options[0].effectInputGainDb).isEqualTo(plan.options[1].effectInputGainDb)
        assertThat(plan.options[0].matchGainDb).isEqualTo(0f)
    }

    @Test fun broadbandPlusSixIsSixDbQuieterThanOriginal() {
        val plan = AbGainPlanner.resultsAb(flatCurve(6.0), flatCurve(6.0), predictor)
        assertThat(plan.options[1].deltaLDb).isWithin(1e-6).of(6.0)
        assertThat(plan.options[1].effectInputGainDb).isWithin(1e-4f).of(plan.options[0].effectInputGainDb - 6f)
        assertThat(plan.options[1].matchGainDb).isWithin(1e-4f).of(-6f)
        assertThat(plan.options[0].effectInputGainDb).isEqualTo(plan.commonPreampDb)
    }

    @Test fun commonPreampProtectsAgainstClippingOfAnyOption() {
        val low = AbCurve.Curve(flatCurve(3.0), flatCurve(2.0))
        val high = AbCurve.Curve(flatCurve(1.0), flatCurve(9.0))
        val plan = AbGainPlanner.preferenceRound(low, high, predictor)
        assertThat(plan.commonPreampDb).isWithin(1e-6f).of(-9.5f)
        val peaks = listOf(3.0f, 9.0f)
        plan.options.forEachIndexed { i, o -> assertThat(o.effectInputGainDb + peaks[i]).isLessThan(0.0f) }
    }

    @Test fun preampFloorIsMinus15() {
        val plan = AbGainPlanner.plan(listOf(AbCurve.Curve(flatCurve(20.0), flatCurve(20.0))), predictor)
        assertThat(plan.commonPreampDb).isEqualTo(-15f)
    }

    @Test fun configForUsesCommonPreamp() {
        val c = AbCurve.Curve(flatCurve(4.0), flatCurve(4.0))
        val plan = AbGainPlanner.resultsAb(c.gLeft, c.gRight, predictor)
        val cfg = AbGainPlanner.configFor(c, plan)
        assertThat(cfg.preampDb).isEqualTo(plan.commonPreampDb)
        assertThat(cfg.gainsDb(Ear.LEFT).max()).isWithin(0.01f).of(4f)
        val flat = AbGainPlanner.configFor(AbCurve.Flat, plan)
        assertThat(flat.gainsDb(Ear.LEFT).all { it == 0f }).isTrue()
    }
}
