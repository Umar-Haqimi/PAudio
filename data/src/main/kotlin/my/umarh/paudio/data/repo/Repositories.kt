package my.umarh.paudio.data.repo

import kotlinx.coroutines.flow.Flow
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity

data class ProfileWithHeadset(val profile: ProfileEntity, val headset: HeadsetEntity?)

data class ListenerProfiles(val listener: ListenerEntity, val profiles: List<ProfileWithHeadset>)

/** (listener, headset) slot a profile occupies; `headsetId == null` = "Any headphones". */
data class ProfileSlot(val listenerId: Long, val headsetId: Long?)

data class ProfileSettings(
    val compStrength: Float = 0.5f,
    val gB: Float = 0f,
    val gP: Float = 0f,
    val gT: Float = 0f,
    val personalizationOn: Boolean = true,
)

sealed interface ProfileWriteResult {
    data class Ok(val profileId: Long) : ProfileWriteResult

    /** The target slot is already occupied by [existingProfileId]. Nothing was changed. */
    data class Conflict(val existingProfileId: Long) : ProfileWriteResult

    data object NotFound : ProfileWriteResult
}

data class HearingTestWithData(
    val test: HearingTestEntity,
    val thresholds: List<ThresholdEntity>,
    val trials: List<TrialEntity>,
)

interface ListenerRepository {
    fun observeAll(): Flow<List<ListenerEntity>>
    fun observe(id: Long): Flow<ListenerEntity?>
    suspend fun get(id: Long): ListenerEntity?
    suspend fun create(name: String): Long
    suspend fun getOrCreate(name: String): Long
    suspend fun rename(id: Long, name: String)

    /** Deletes the listener and (cascade) all of its profiles and tests. */
    suspend fun delete(id: Long)
}

interface HeadsetRepository {
    fun observeAll(): Flow<List<HeadsetEntity>>
    suspend fun getByKey(key: HeadsetKey): HeadsetEntity?

    /** Returns the headset id, inserting it when the key is new. */
    suspend fun getOrCreate(device: OutputDevice, displayName: String = device.productName): Long
    suspend fun getOrCreate(key: HeadsetKey, displayName: String, device: OutputDevice): Long
}

interface ProfileRepository {
    fun observeAll(): Flow<List<ListenerProfiles>>
    fun observe(profileId: Long): Flow<ProfileWithHeadset?>
    suspend fun get(profileId: Long): ProfileWithHeadset?

    suspend fun create(
        listenerId: Long,
        headsetId: Long?,
        name: String,
        settings: ProfileSettings = ProfileSettings(),
    ): ProfileWriteResult

    suspend fun rename(profileId: Long, name: String)
    suspend fun updateSettings(profileId: Long, settings: ProfileSettings)
    suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean)
    suspend fun delete(profileId: Long)

    /**
     * Copies settings plus the whole tests history (tests, thresholds, trials, preference tests) in one
     * transaction into [target]. Because (listener, headset) is unique, [target] must be a free slot
     * (e.g. another headset or listener); otherwise [ProfileWriteResult.Conflict] is returned.
     */
    suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot): ProfileWriteResult

    /** Fails cleanly with [ProfileWriteResult.Conflict] when the target slot is occupied. */
    suspend fun moveToHeadset(profileId: Long, headsetId: Long?): ProfileWriteResult

    /** Returns the id of the listener's "Any headphones" profile, creating it when missing. */
    suspend fun ensureAnyHeadphonesProfile(listenerId: Long): Long

    /** The profile of [listenerId] bound to exactly this headset, or null (no Any fallback here). */
    suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey): ProfileEntity?
}

interface HearingTestRepository {
    /**
     * Saves test + thresholds + trials atomically; ids/testId in the arguments are overwritten.
     * Pass the headset the test was taken on as [headsetKey]: it is stamped into deviceSnapshotJson
     * (see DeviceSnapshot) so trends stay correct if the profile is later moved to another headset.
     */
    suspend fun save(
        test: HearingTestEntity,
        thresholds: List<ThresholdEntity>,
        trials: List<TrialEntity> = emptyList(),
        headsetKey: HeadsetKey? = null,
    ): Long

    fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>>
    fun observeLatest(profileId: Long): Flow<HearingTestEntity?>
    suspend fun latest(profileId: Long): HearingTestEntity?
    suspend fun get(testId: Long): HearingTestEntity?
    suspend fun thresholds(testId: Long): List<ThresholdEntity>
    fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>>
    suspend fun trials(testId: Long): List<TrialEntity>
    suspend fun delete(testId: Long)

    /**
     * Tests of [profileId] taken on [headsetKey], newest first. A test belongs to a headset by the
     * `headsetKey` in its deviceSnapshotJson; tests without one count for the profile's current headset.
     */
    fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>>
}

interface PreferenceTestRepository {
    suspend fun save(test: PreferenceTestEntity): Long
    fun observeForProfile(profileId: Long): Flow<List<PreferenceTestEntity>>
    suspend fun latest(profileId: Long): PreferenceTestEntity?
}
