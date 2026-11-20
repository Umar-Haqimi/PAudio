package my.umarh.paudio.data

import android.content.Context
import androidx.room.Room
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.PAudioDatabase
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.RoomHeadsetRepository
import my.umarh.paudio.data.repo.RoomHearingTestRepository
import my.umarh.paudio.data.repo.RoomListenerRepository
import my.umarh.paudio.data.repo.RoomPreferenceTestRepository
import my.umarh.paudio.data.repo.RoomProfileRepository
import my.umarh.paudio.data.repo.ProfileWriteResult

class Env(context: Context) {
    val db: PAudioDatabase =
        Room.inMemoryDatabaseBuilder(context, PAudioDatabase::class.java)
            .addCallback(my.umarh.paudio.data.db.AnySlotIndexCallback).allowMainThreadQueries().build()
    val listeners = RoomListenerRepository(db)
    val headsets = RoomHeadsetRepository(db)
    val settings = FakeSettings()
    val profiles = RoomProfileRepository(db, settings)
    val tests = RoomHearingTestRepository(db)
    val prefs = RoomPreferenceTestRepository(db)
    fun close() = db.close()
}

val WIRED = OutputDevice(OutputDeviceType.WIRED, "USB-C Buds")
val BT = OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, "Buds Pro", "AB:CD")

fun quickTest(profileId: Long, startedAt: Long, headsetKey: String?) = HearingTestEntity(
    profileId = profileId, mode = TestMode.QUICK, startedAt = startedAt, durationMs = 90_000, volIndex = 7, volMax = 15,
    lRef = -42.5f,
    deviceSnapshotJson = headsetKey?.let { DeviceSnapshot.withHeadsetKey("{\"codec\":\"aac\",\"n\":[1,2]}", it) },
    ancOn = false, appVersion = "1.0.0", algoVersion = 1, fpRate = 0.1f, reliable = true,
)

fun thresholds() = listOf(
    ThresholdEntity(0, Ear.LEFT, 1000, -40f, censored = false, lowConfidence = false, trials = 6),
    ThresholdEntity(0, Ear.LEFT, 8000, -20.25f, censored = true, lowConfidence = false, trials = 9),
    ThresholdEntity(0, Ear.RIGHT, 1000, null, censored = false, lowConfidence = true, trials = 3),
    ThresholdEntity(0, Ear.RIGHT, 4000, -55.5f, censored = false, lowConfidence = true, trials = 5),
)

fun quickTrials() = listOf(
    TrialEntity(0, 0, Ear.LEFT, 1000, -30f, isCatch = false, response = true, tMs = 100, run = null),
    TrialEntity(0, 1, Ear.RIGHT, 1000, null, isCatch = true, response = false, tMs = 1500, run = null),
    TrialEntity(0, 2, Ear.LEFT, 8000, -22.5f, isCatch = false, response = false, tMs = 3000, run = null),
)

fun thoroughTrials() = listOf(
    TrialEntity(0, 0, Ear.LEFT, 1000, -41f, isCatch = false, response = false, tMs = 900, run = "DESC"),
    TrialEntity(0, 1, Ear.LEFT, 1000, -45f, isCatch = false, response = true, tMs = 2400, run = "ASC"),
)

fun thoroughTest(profileId: Long, startedAt: Long, headsetKey: String?) =
    quickTest(profileId, startedAt, headsetKey).copy(mode = TestMode.THOROUGH, fpRate = null, ancOn = null, reliable = false)

fun prefTest(profileId: Long, startedAt: Long) = PreferenceTestEntity(
    profileId = profileId, startedAt = startedAt, gB = 3.5f, gP = -1f, gT = 2f,
    roundsJson = "[{\"a\":1,\"pick\":\"B\"},{\"a\":2}]",
)

/** Listener "Alice" + BT headset profile with 2 tests (Quick, Thorough) and a preference test. Returns profile id. */
suspend fun buildRichProfile(env: Env, listenerName: String = "Alice"): Long {
    val l = env.listeners.getOrCreate(listenerName)
    val h = env.headsets.getOrCreate(BT, "My Buds")
    val ok = env.profiles.create(l, h, "Commute", ProfileSettings(0.75f, 3f, -2f, 4f, false)) as ProfileWriteResult.Ok
    val p = ok.profileId
    env.profiles.setSeedColor(p, 0xFF336699.toInt(), true)
    val key = BT.headsetKey.value
    env.tests.save(quickTest(p, 1_000, key), thresholds(), quickTrials())
    env.tests.save(thoroughTest(p, 2_000, key), thresholds(), thoroughTrials())
    env.prefs.save(prefTest(p, 3_000))
    return p
}

class FakeSettings : my.umarh.paudio.data.settings.SettingsRepository {
    val map = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    override val activeListenerId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    override val headsetProfiles = map
    override val engineMode = kotlinx.coroutines.flow.MutableStateFlow(my.umarh.paudio.data.settings.EngineMode.SESSION)
    override val startOnBoot = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val onboardingDone = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val defaultCompStrength = kotlinx.coroutines.flow.MutableStateFlow(0.5f)
    override val personalizationEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)
    override suspend fun setActiveListenerId(id: Long?) { activeListenerId.value = id }
    override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) {
        map.value = if (profileId == null) map.value - headsetKey else map.value + (headsetKey to profileId)
    }
    override suspend fun removeProfile(profileId: Long) { map.value = map.value.filterValues { it != profileId } }
    override suspend fun setEngineMode(mode: my.umarh.paudio.data.settings.EngineMode) { engineMode.value = mode }
    override suspend fun setStartOnBoot(enabled: Boolean) { startOnBoot.value = enabled }
    override suspend fun setOnboardingDone(done: Boolean) { onboardingDone.value = done }
    override suspend fun setDefaultCompStrength(strength: Float) { defaultCompStrength.value = strength }
    override suspend fun setPersonalizationEnabled(enabled: Boolean) { personalizationEnabled.value = enabled }
}
