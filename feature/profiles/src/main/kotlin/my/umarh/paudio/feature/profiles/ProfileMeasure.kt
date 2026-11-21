package my.umarh.paudio.feature.profiles

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import my.umarh.paudio.core.audiometry.MeasurementModel
import my.umarh.paudio.core.audiometry.ThresholdPoint
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.designsystem.ProfileSeedColor

/** HL_est per ear in [freqsHz] order (input of the hearing butterfly). */
@Immutable
data class ButterflyData(val leftHl: List<Float?>, val rightHl: List<Float?>, val freqsHz: List<Int>)

/** Pure mapping from raw stored thresholds to the pieces Profiles (and the app theme) need. */
object ProfileMeasure {
    /** Null when the threshold set is incomplete (every grid point of both ears is required). */
    fun butterfly(thresholds: List<ThresholdEntity>): ButterflyData? {
        val byEar = Ear.entries.associateWith { ear ->
            thresholds.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
        }
        if (!byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }) return null
        val points = byEar.mapValues { (_, pts) ->
            FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
                val t = pts.getValue(f)
                ThresholdPoint(f, t.levelDbfs!!.toDouble(), t.censored)
            }
        }
        val r = MeasurementModel.compute(points)
        fun hl(ear: Ear): List<Float?> = r.hlEst.getValue(ear).map { it.toFloat() }
        return ButterflyData(hl(Ear.LEFT), hl(Ear.RIGHT), r.freqsHz)
    }

    /** The F25 seed colour (ARGB) of a complete test; null when the thresholds are incomplete. */
    fun seedColor(thresholds: List<ThresholdEntity>): Int? {
        val b = butterfly(thresholds) ?: return null
        return ProfileSeedColor.from(b.leftHl, b.rightHl, b.freqsHz).toArgb()
    }
}
