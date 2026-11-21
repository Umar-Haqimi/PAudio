package my.umarh.paudio.feature.hearingtest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.ProfileSlot
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.EngineMode
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.testing.FakeOutputDevices
import org.junit.Test

private class MemListeners : ListenerRepository {
    val rows = mutableListOf<ListenerEntity>()
    override fun observeAll(): Flow<List<ListenerEntity>> = flowOf(rows.toList())
    override fun observe(id: Long): Flow<ListenerEntity?> = flowOf(rows.firstOrNull { it.id == id })
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun create(name: String): Long {
        val id = rows.size + 1L
        rows += ListenerEntity(id, name, 0)
        return id
    }
    override suspend fun getOrCreate(name: String) = rows.firstOrNull { it.name == name }?.id ?: create(name)
    override suspend fun rename(id: Long, name: String) = Unit
    override suspend fun delete(id: Long) = Unit
}

private class MemHeadsets : HeadsetRepository {
    val rows = mutableListOf<HeadsetEntity>()
    override fun observeAll(): Flow<List<HeadsetEntity>> = flowOf(rows.toList())
    override suspend fun getByKey(key: HeadsetKey) = rows.firstOrNull { it.key == key.value }
    override suspend fun getOrCreate(device: OutputDevice, displayName: String): Long = getOrCreate(device.headsetKey, displayName, device)
    override suspend fun getOrCreate(key: HeadsetKey, displayName: String, device: OutputDevice): Long {
        rows.firstOrNull { it.key == key.value }?.let { return it.id }
        val id = rows.size + 1L
        rows += HeadsetEntity(id, key.value, displayName, device.type, device.productName, device.addressSuffix, 0)
        return id
    }
}

private class MemProfiles(val headsets: MemHeadsets) : ProfileRepository {
    val rows = mutableListOf<ProfileEntity>()
    override fun observeAll(): Flow<List<ListenerProfiles>> = flowOf(emptyList())
    override fun observe(profileId: Long): Flow<ProfileWithHeadset?> = flowOf(null)
    override suspend fun get(profileId: Long): ProfileWithHeadset? =
        rows.firstOrNull { it.id == profileId }?.let { p -> ProfileWithHeadset(p, headsets.rows.firstOrNull { it.id == p.headsetId }) }
    override suspend fun create(listenerId: Long, headsetId: Long?, name: String, settings: ProfileSettings): ProfileWriteResult {
        rows.firstOrNull { it.listenerId == listenerId && it.headsetId == headsetId }?.let { return ProfileWriteResult.Conflict(it.id) }
        val id = rows.size + 1L
        rows += ProfileEntity(id = id, listenerId = listenerId, headsetId = headsetId, name = name, createdAt = 0, updatedAt = 0)
        return ProfileWriteResult.Ok(id)
    }
    override suspend fun rename(profileId: Long, name: String) = Unit
    override suspend fun updateSettings(profileId: Long, settings: ProfileSettings) = Unit
    override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) = Unit
    override suspend fun delete(profileId: Long) = Unit
    override suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot) = ProfileWriteResult.NotFound
    override suspend fun moveToHeadset(profileId: Long, headsetId: Long?) = ProfileWriteResult.NotFound
    override suspend fun ensureAnyHeadphonesProfile(listenerId: Long) = 0L
    override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey): ProfileEntity? {
        val h = headsets.rows.firstOrNull { it.key == headsetKey.value } ?: return null
        return rows.firstOrNull { it.listenerId == listenerId && it.headsetId == h.id }
    }
}

private class MemTests : HearingTestRepository {
    var lastKey: HeadsetKey? = null
    var lastTest: HearingTestEntity? = null
    var count = 0
    override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<TrialEntity>, headsetKey: HeadsetKey?): Long {
        lastKey = headsetKey
        lastTest = test
        return (++count).toLong()
    }
    override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> = flowOf(emptyList())
    override fun observeLatest(profileId: Long): Flow<HearingTestEntity?> = flowOf(null)
    override suspend fun latest(profileId: Long): HearingTestEntity? = null
    override suspend fun get(testId: Long): HearingTestEntity? = null
    override suspend fun thresholds(testId: Long): List<ThresholdEntity> = emptyList()
    override fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>> = flowOf(emptyList())
    override suspend fun trials(testId: Long): List<TrialEntity> = emptyList()
    override suspend fun delete(testId: Long) = Unit
    override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> = flowOf(emptyList())
}

private class MemSettings : SettingsRepository {
    val active = MutableStateFlow<Long?>(null)
    override val activeListenerId: Flow<Long?> = active
    override val headsetProfiles: Flow<Map<String, Long>> = flowOf(emptyMap())
    override val engineMode: Flow<EngineMode> = flowOf(EngineMode.SESSION)
    override val startOnBoot: Flow<Boolean> = flowOf(false)
    override val onboardingDone: Flow<Boolean> = flowOf(true)
    override val defaultCompStrength: Flow<Float> = flowOf(0.5f)
    override val personalizationEnabled: Flow<Boolean> = flowOf(true)
    override suspend fun setActiveListenerId(id: Long?) { active.value = id }
    override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) = Unit
    override suspend fun removeProfile(profileId: Long) = Unit
    override suspend fun setEngineMode(mode: EngineMode) = Unit
    override suspend fun setStartOnBoot(enabled: Boolean) = Unit
    override suspend fun setOnboardingDone(done: Boolean) = Unit
    override suspend fun setDefaultCompStrength(strength: Float) = Unit
    override suspend fun setPersonalizationEnabled(enabled: Boolean) = Unit
}

class RepositoryTestStoreTest {
    private val listeners = MemListeners()
    private val headsets = MemHeadsets()
    private val profiles = MemProfiles(headsets)
    private val tests = MemTests()
    private val settings = MemSettings()
    private val store = RepositoryTestStore(listeners, profiles, headsets, tests, settings, FakePlatform())

    private fun request(profileId: Long?, device: OutputDevice = FakeOutputDevices.BLUETOOTH_BUDS) = SaveRequest(
        profileId, device,
        HearingTestEntity(
            profileId = 0, mode = TestMode.QUICK, startedAt = 1, durationMs = 2, volIndex = 8, volMax = 15, lRef = -60f,
            deviceSnapshotJson = "{}", ancOn = false, appVersion = "t", algoVersion = 1, fpRate = 0f, reliable = true,
        ),
        listOf(ThresholdEntity(0, Ear.LEFT, 1000, -60f, false, false, 5)),
        emptyList(),
    )

    @Test
    fun nullProfileCreatesDefaultListenerHeadsetAndProfileThenReuses() = runBlocking {
        val id1 = store.save(request(null))
        assertThat(listeners.rows.map { it.name }).containsExactly("Me")
        assertThat(settings.active.value).isEqualTo(1L)
        assertThat(headsets.rows).hasSize(1)
        assertThat(profiles.rows).hasSize(1)
        assertThat(profiles.rows.single().name).isEqualTo("Test Buds")
        assertThat(tests.lastTest!!.profileId).isEqualTo(profiles.rows.single().id)
        assertThat(tests.lastKey).isEqualTo(FakeOutputDevices.BLUETOOTH_BUDS.headsetKey)
        store.save(request(null))
        assertThat(profiles.rows).hasSize(1) // reused
        assertThat(id1).isEqualTo(1L)
    }

    @Test
    fun explicitProfileIsUsedAndWrongHeadsetIsBlocked() = runBlocking {
        store.save(request(null)) // creates profile 1 for the buds
        assertThat(store.check(FakeOutputDevices.BLUETOOTH_BUDS, 1)).isEqualTo(TargetCheck.Ok)
        assertThat(store.check(FakeOutputDevices.WIRED_HEADPHONES, 1)).isEqualTo(TargetCheck.WrongHeadset("Test Buds"))
        assertThat(store.check(FakeOutputDevices.BLUETOOTH_BUDS, 99)).isEqualTo(TargetCheck.ProfileMissing)
        store.save(request(1, FakeOutputDevices.BLUETOOTH_BUDS))
        assertThat(tests.lastTest!!.profileId).isEqualTo(1L)
    }

    @Test
    fun speakerAndNoOutputAreNeverTested() = runBlocking {
        assertThat(store.check(FakeOutputDevices.SPEAKER, null)).isEqualTo(TargetCheck.NotProcessable)
        assertThat(store.check(null, null)).isEqualTo(TargetCheck.NotProcessable)
    }
}
