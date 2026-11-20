package my.umarh.paudio.core.audiometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalibrationTest {
    /** A fake curve: index i → −(maxIndex − i)·2.5 dB (15 steps, 0 dB at the top). */
    private val curve: (Int) -> Float? = { i -> if (i <= 0) null else -(15 - i) * 2.5f }

    @Test
    fun rampScheduleAndCap() {
        assertThat(Calibration.rampLevelAtMs(0)).isEqualTo(-90.0)
        assertThat(Calibration.rampLevelAtMs(449)).isEqualTo(-90.0)
        assertThat(Calibration.rampLevelAtMs(450)).isEqualTo(-88.0)
        assertThat(Calibration.rampLevelAtMs(450L * 40)).isEqualTo(-10.0)
        assertThat(Calibration.rampLevelAtMs(450L * 41 - 1)).isEqualTo(-10.0)
        assertThat(Calibration.rampLevelAtMs(450L * 41)).isNull()
        assertThat(Calibration.lRefFromTap(-64.0)).isEqualTo(-68.0)
        assertThat(Calibration.initialIndex(15)).isEqualTo(8) // round(7.5)
        assertThat(Calibration.initialIndex(25)).isEqualTo(13)
    }

    @Test
    fun windows() {
        assertThat(Calibration.inTargetWindow(-68.0)).isTrue()
        assertThat(Calibration.inTargetWindow(-50.0)).isTrue()
        assertThat(Calibration.inTargetWindow(-68.1)).isFalse()
        assertThat(Calibration.inAcceptWindow(-75.0)).isTrue()
        assertThat(Calibration.inAcceptWindow(-35.0)).isTrue()
        assertThat(Calibration.inAcceptWindow(-75.1)).isFalse()
    }

    @Test
    fun indexPlanRaisesLRefWhenVolumeLowered() {
        // L_ref = −80 (too quiet a tone was needed → volume is too high). Lowering the volume by X dB raises L_ref by X.
        // Current index 10 (−12.5 dB). Need +20 dB → index with dB = −32.5 → below the curve minimum, so pick the lowest: index 1 (−35).
        val plan = Calibration.planIndexChange(10, 1, 15, -80.0, curve)
        // Index 2 (−32.5 dB): L_ref' = −80 + (−12.5 − −32.5) = −60.0 exactly on the aim; index 1 would give −57.5.
        assertThat(plan).isEqualTo(2)
        assertThat(Calibration.predictLRef(-80.0, 10, 2, curve)).isEqualTo(-60.0)
        assertThat(Calibration.predictLRef(-80.0, 10, 1, curve)).isEqualTo(-57.5)
    }

    @Test
    fun indexPlanLowersVolumeDirectionAndRaisesIndexWhenTooQuiet() {
        // L_ref = −40: the tone was loud → system volume too low → raise the index so L_ref falls toward −60.
        val plan = Calibration.planIndexChange(6, 1, 15, -40.0, curve)
        // current dB = −22.5; raising to index 14 (−2.5 dB) moves L_ref by −20 → −60.0 exactly (index 15 would give −62.5).
        assertThat(plan).isEqualTo(14)
        assertThat(Calibration.predictLRef(-40.0, 6, 14, curve)).isEqualTo(-60.0)
    }

    @Test
    fun noChangeWhenCurrentIsBestOrNoDbPath() {
        assertThat(Calibration.planIndexChange(8, 1, 15, -60.0, curve)).isNull()
        // −∞ / unknown dB for the current index → fallback nudge in the right direction
        val noDb: (Int) -> Float? = { null }
        assertThat(Calibration.planIndexChange(8, 1, 15, -80.0, noDb)).isEqualTo(6) // nudge by max(1, round(0.1·max)) = 2, downward
        assertThat(Calibration.planIndexChange(8, 1, 15, -40.0, noDb)).isEqualTo(10)
        assertThat(Calibration.planIndexChange(15, 1, 15, -40.0, noDb)).isNull()
        val ninf: (Int) -> Float? = { i -> if (i == 8) Float.NEGATIVE_INFINITY else -1f }
        assertThat(Calibration.planIndexChange(8, 1, 15, -80.0, ninf)).isEqualTo(6)
    }

    @Test
    fun sessionAcceptsInWindowImmediately() {
        val s = CalibrationSession(1, 15, curve)
        assertThat(s.currentIndex).isEqualTo(8)
        val o = s.onTap(-64.0) // L_ref = −68 : edge of window
        assertThat(o).isInstanceOf(CalibrationOutcome.Accepted::class.java)
        val r = (o as CalibrationOutcome.Accepted).result
        assertThat(r.warning).isFalse()
        assertThat(r.lRefDbfs).isEqualTo(-68.0)
        assertThat(r.index).isEqualTo(8)
        assertThat(r.maxIndex).isEqualTo(15)
    }

    @Test
    fun sessionRerunsThenAcceptsWithWarningOrAborts() {
        // Out of window every time (curve says nothing helps: flat curve → planIndexChange returns null → immediate verdict).
        val flat: (Int) -> Float? = { 0f }
        val s = CalibrationSession(1, 15, flat)
        val o = s.onTap(-60.0 + 4 - 20.0) // L_ref = −80 → outside accept window, no better index
        assertThat(o).isEqualTo(CalibrationOutcome.Aborted(CalibrationAbortReason.VOLUME_RANGE_UNSUITABLE, -80.0))

        // Realistic: reruns planned from the curve, then accept with a warning inside [−75, −35].
        val s2 = CalibrationSession(1, 15, curve)
        val o1 = s2.onTap(-72.0) // L_ref −76: outside the accept window → rerun at a quieter index
        assertThat(o1).isEqualTo(CalibrationOutcome.Rerun(2, -76.0))
        s2.restart(2)
        assertThat(s2.reruns).isEqualTo(1)
        val o2 = s2.onTap(-70.0) // L_ref −74
        assertThat(o2).isInstanceOf(CalibrationOutcome.Rerun::class.java)
        s2.restart((o2 as CalibrationOutcome.Rerun).newIndex)
        assertThat(s2.reruns).isEqualTo(Calibration.MAX_RERUNS)
        val o3 = s2.onTap(-70.0) // L_ref −74: reruns exhausted, inside accept window, outside target
        assertThat(o3).isInstanceOf(CalibrationOutcome.Accepted::class.java)
        assertThat((o3 as CalibrationOutcome.Accepted).result.warning).isTrue()

        val s3 = CalibrationSession(1, 15, curve)
        val r1 = s3.onTap(-90.0) // L_ref −94
        assertThat(r1).isInstanceOf(CalibrationOutcome.Rerun::class.java)
        s3.restart((r1 as CalibrationOutcome.Rerun).newIndex)
        assertThat(s3.onTap(-90.0))
            .isEqualTo(CalibrationOutcome.Aborted(CalibrationAbortReason.VOLUME_RANGE_UNSUITABLE, -94.0))
    }

    @Test
    fun cantHearOutcome() {
        val s = CalibrationSession(1, 15, curve)
        assertThat(s.rampLevelAtMs(450L * 41)).isNull()
        assertThat(s.onRampExhausted()).isEqualTo(CalibrationOutcome.Aborted(CalibrationAbortReason.CANT_HEAR))
    }

    @Test
    fun iso389TableAndBounds() {
        assertThat(Iso389_7.rdf(1000)).isEqualTo(0.8)
        assertThat(Iso389_7.rdf(14000)).isEqualTo(23.2)
        // interpolation in log2 f between 1000 (0.8) and 1250 (1.9)
        val f = 1118.0339887498949 // geometric midpoint → halfway in log2
        assertThat(Iso389_7.rdf(f)).isWithin(1e-9).of(1.35)
        assertThat(Iso389_7.lExp(14000, -60.0)).isWithin(1e-9).of(-60.0 + 22.4)
        assertThat(Iso389_7.lMax(14000, -60.0)).isEqualTo(-3.0) // min(−3, −37.6 + 50)
        assertThat(Iso389_7.lMax(1000, -60.0)).isEqualTo(-10.0)
        assertThat(Iso389_7.lMin(1000, -60.0)).isEqualTo(-85.0)
        assertThat(Iso389_7.lMax(1000, -50.0)).isEqualTo(-3.0)
    }
}
