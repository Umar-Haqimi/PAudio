package my.umarh.paudio.data.db

import my.umarh.paudio.core.audiometry.MeasurementResult
import my.umarh.paudio.core.audiometry.RawThreshold
import my.umarh.paudio.core.audiometry.measureThresholds

fun ThresholdEntity.toRaw() = RawThreshold(ear, freqHz, levelDbfs?.toDouble(), censored)

/** Measurement of a stored test; null when incomplete. The single entity -> MeasurementModel mapping. */
fun List<ThresholdEntity>.measure(): MeasurementResult? = measureThresholds(map { it.toRaw() })
