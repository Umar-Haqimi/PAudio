package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid

/** One stored threshold as plain values (no persistence types); [levelDbfs] null = no value was stored. */
data class RawThreshold(val ear: Ear, val freqHz: Int, val levelDbfs: Double?, val censored: Boolean)

/**
 * The one place that turns stored thresholds into [MeasurementResult] (Results, Home, Profiles, Health, seed colour).
 * Returns null when the set is incomplete: every grid point of both ears needs a level. Values are raw (unclamped);
 * display clamping is the caller's concern ([MeasurementModel.displayHl]).
 */
fun measureThresholds(raw: Iterable<RawThreshold>): MeasurementResult? {
    val byEar = Ear.entries.associateWith { ear ->
        raw.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
    }
    if (!byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }) return null
    val points = byEar.mapValues { (_, pts) ->
        FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
            val t = pts.getValue(f)
            ThresholdPoint(f, t.levelDbfs!!, t.censored)
        }
    }
    return MeasurementModel.compute(points)
}
