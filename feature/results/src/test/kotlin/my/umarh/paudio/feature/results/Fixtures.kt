package my.umarh.paudio.feature.results

import my.umarh.paudio.core.audiometry.Iso389_7
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ThresholdEntity

const val L_REF = -60.0

/** Builds thresholds so that HL_est == the given hl when both ears are 0 at 1 and 2 kHz (anchor = L_REF). */
fun thresholds(
    testId: Long,
    right: (Int) -> Double = { 0.0 },
    left: (Int) -> Double = { 0.0 },
    censoredAt: Set<Pair<Ear, Int>> = emptySet(),
    lowConfidenceAt: Set<Pair<Ear, Int>> = emptySet(),
): List<ThresholdEntity> = Ear.entries.flatMap { ear ->
    FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
        val hl = if (ear == Ear.RIGHT) right(f) else left(f)
        ThresholdEntity(
            testId = testId,
            ear = ear,
            freqHz = f,
            levelDbfs = (L_REF + Iso389_7.rdf(f) + hl).toFloat(),
            censored = (ear to f) in censoredAt,
            lowConfidence = (ear to f) in lowConfidenceAt,
            trials = 8,
        )
    }
}

fun test(
    id: Long,
    profileId: Long = 1,
    startedAt: Long = 1_700_000_000_000L + id * 86_400_000L,
    reliable: Boolean = true,
    headsetKey: String? = "WIRED_HEADPHONES|AKG|",
) = HearingTestEntity(
    id = id,
    profileId = profileId,
    mode = TestMode.QUICK,
    startedAt = startedAt,
    durationMs = 400_000,
    volIndex = 8,
    volMax = 15,
    lRef = L_REF.toFloat(),
    deviceSnapshotJson = headsetKey?.let { DeviceSnapshot.withHeadsetKey(null, it) },
    ancOn = null,
    appVersion = "1",
    algoVersion = 1,
    fpRate = 0.05f,
    reliable = reliable,
)
