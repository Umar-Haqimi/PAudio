package my.umarh.paudio.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import my.umarh.paudio.core.audiometry.Iso389_7
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.db.DeviceSnapshot
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

/** In-memory stand-in for the Room database, shared by the fake repositories of one test. */
class FakeDb {
    private var nextId = 100L
    fun id() = nextId++

    val listeners = MutableStateFlow<List<ListenerEntity>>(emptyList())
    val headsets = MutableStateFlow<List<HeadsetEntity>>(emptyList())
    val profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val tests = MutableStateFlow<List<HearingTestEntity>>(emptyList())
    val thresholds = mutableMapOf<Long, List<ThresholdEntity>>()

    fun addListener(name: String, id: Long = id()): ListenerEntity =
        ListenerEntity(id = id, name = name, createdAt = 0).also { listeners.value += it }

    fun addHeadset(key: String, name: String = key, id: Long = id()): HeadsetEntity = HeadsetEntity(
        id = id, key = key, displayName = name, type = my.umarh.paudio.core.model.OutputDeviceType.BLUETOOTH_A2DP,
        productName = name, addressSuffix = "", createdAt = 0,
    ).also { headsets.value += it }

    fun addProfile(listenerId: Long, headsetId: Long?, name: String, id: Long = id(), seedColor: Int? = null, overridden: Boolean = false) =
        ProfileEntity(
            id = id, listenerId = listenerId, headsetId = headsetId, name = name, createdAt = 0, updatedAt = 0,
            seedColor = seedColor, seedOverridden = overridden,
        ).also { profiles.value += it }

    fun addTest(profileId: Long, startedAt: Long = 1_700_000_000_000L, complete: Boolean = true, reliable: Boolean = true): Long {
        val id = id()
        tests.value += HearingTestEntity(
            id = id, profileId = profileId, mode = TestMode.QUICK, startedAt = startedAt, durationMs = 400_000,
            volIndex = 8, volMax = 15, lRef = -60f, deviceSnapshotJson = DeviceSnapshot.withHeadsetKey(null, "k"),
            ancOn = null, appVersion = "1", algoVersion = 1, fpRate = 0f, reliable = reliable,
        )
        thresholds[id] = fullThresholds(id, complete)
        return id
    }

    fun profile(id: Long) = profiles.value.firstOrNull { it.id == id }
}

fun fullThresholds(testId: Long, complete: Boolean = true, leftExtraHl: (Int) -> Double = { 0.0 }): List<ThresholdEntity> =
    Ear.entries.flatMap { ear ->
        FrequencyGrid.TEST_FREQUENCIES_HZ.mapNotNull { f ->
            if (!complete && ear == Ear.LEFT && f == 14000) return@mapNotNull null
            val hl = if (ear == Ear.LEFT) leftExtraHl(f) else 0.0
            ThresholdEntity(testId, ear, f, (-60.0 + Iso389_7.rdf(f) + hl).toFloat(), false, false, 8)
        }
    }

class FakeListenerRepo(private val db: FakeDb) : ListenerRepository {
    override fun observeAll(): Flow<List<ListenerEntity>> = db.listeners
    override fun observe(id: Long) = db.listeners.map { l -> l.firstOrNull { it.id == id } }
    override suspend fun get(id: Long) = db.listeners.value.firstOrNull { it.id == id }
    override suspend fun create(name: String): Long = db.addListener(name).id
    override suspend fun getOrCreate(name: String): Long =
        db.listeners.value.firstOrNull { it.name == name }?.id ?: create(name)

    override suspend fun rename(id: Long, name: String) {
        db.listeners.value = db.listeners.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun delete(id: Long) {
        db.listeners.value = db.listeners.value.filterNot { it.id == id }
        db.profiles.value = db.profiles.value.filterNot { it.listenerId == id }
    }
}

class FakeHeadsetRepo(private val db: FakeDb) : HeadsetRepository {
    override fun observeAll(): Flow<List<HeadsetEntity>> = db.headsets
    override suspend fun getByKey(key: HeadsetKey) = db.headsets.value.firstOrNull { it.key == key.value }
    override suspend fun getOrCreate(device: OutputDevice, displayName: String): Long =
        getOrCreate(device.headsetKey, displayName, device)

    override suspend fun getOrCreate(key: HeadsetKey, displayName: String, device: OutputDevice): Long =
        getByKey(key)?.id ?: db.addHeadset(key.value, displayName).id
}

class FakeProfileRepo(private val db: FakeDb) : ProfileRepository {
    private fun withHeadset(p: ProfileEntity) = ProfileWithHeadset(p, db.headsets.value.firstOrNull { it.id == p.headsetId })

    override fun observeAll(): Flow<List<ListenerProfiles>> = combineAll()

    private fun combineAll(): Flow<List<ListenerProfiles>> =
        kotlinx.coroutines.flow.combine(db.listeners, db.profiles, db.headsets) { ls, ps, _ ->
            ls.map { l -> ListenerProfiles(l, ps.filter { it.listenerId == l.id }.map(::withHeadset)) }
        }

    override fun observe(profileId: Long): Flow<ProfileWithHeadset?> =
        kotlinx.coroutines.flow.combine(db.profiles, db.headsets) { ps, _ ->
            ps.firstOrNull { it.id == profileId }?.let(::withHeadset)
        }

    override suspend fun get(profileId: Long) = db.profile(profileId)?.let(::withHeadset)

    private fun slotTaken(listenerId: Long, headsetId: Long?, except: Long? = null) =
        db.profiles.value.firstOrNull { it.listenerId == listenerId && it.headsetId == headsetId && it.id != except }

    override suspend fun create(listenerId: Long, headsetId: Long?, name: String, settings: ProfileSettings): ProfileWriteResult {
        slotTaken(listenerId, headsetId)?.let { return ProfileWriteResult.Conflict(it.id) }
        val p = db.addProfile(listenerId, headsetId, name)
        db.profiles.value = db.profiles.value.map {
            if (it.id == p.id) it.copy(compStrength = settings.compStrength, gB = settings.gB, gP = settings.gP, gT = settings.gT) else it
        }
        return ProfileWriteResult.Ok(p.id)
    }

    override suspend fun rename(profileId: Long, name: String) = update(profileId) { it.copy(name = name) }

    override suspend fun updateSettings(profileId: Long, settings: ProfileSettings) = update(profileId) {
        it.copy(
            compStrength = settings.compStrength, gB = settings.gB, gP = settings.gP, gT = settings.gT,
            personalizationOn = settings.personalizationOn,
        )
    }

    override suspend fun setPersonalization(profileId: Long, on: Boolean) = update(profileId) { it.copy(personalizationOn = on) }

    override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) =
        update(profileId) { it.copy(seedColor = seedColor, seedOverridden = overridden) }

    private fun update(id: Long, f: (ProfileEntity) -> ProfileEntity) {
        db.profiles.value = db.profiles.value.map { if (it.id == id) f(it) else it }
    }

    override suspend fun delete(profileId: Long) {
        db.profiles.value = db.profiles.value.filterNot { it.id == profileId }
        db.tests.value = db.tests.value.filterNot { it.profileId == profileId }
    }

    override suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot): ProfileWriteResult {
        val src = db.profile(profileId) ?: return ProfileWriteResult.NotFound
        slotTaken(target.listenerId, target.headsetId)?.let { return ProfileWriteResult.Conflict(it.id) }
        val copy = db.addProfile(target.listenerId, target.headsetId, newName)
        db.profiles.value = db.profiles.value.map {
            if (it.id == copy.id) it.copy(compStrength = src.compStrength, gB = src.gB, gP = src.gP, gT = src.gT) else it
        }
        db.tests.value.filter { it.profileId == profileId }.forEach { t ->
            val id = db.id()
            db.tests.value += t.copy(id = id, profileId = copy.id)
            db.thresholds[id] = db.thresholds[t.id].orEmpty().map { it.copy(testId = id) }
        }
        return ProfileWriteResult.Ok(copy.id)
    }

    override suspend fun moveToHeadset(profileId: Long, headsetId: Long?): ProfileWriteResult {
        val p = db.profile(profileId) ?: return ProfileWriteResult.NotFound
        slotTaken(p.listenerId, headsetId, except = profileId)?.let { return ProfileWriteResult.Conflict(it.id) }
        update(profileId) { it.copy(headsetId = headsetId) }
        return ProfileWriteResult.Ok(profileId)
    }

    override suspend fun ensureAnyHeadphonesProfile(listenerId: Long): Long =
        slotTaken(listenerId, null)?.id ?: db.addProfile(listenerId, null, "Any headphones").id

    override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey): ProfileEntity? {
        val h = db.headsets.value.firstOrNull { it.key == headsetKey.value } ?: return null
        return slotTaken(listenerId, h.id)
    }
}

class FakeTestRepo(private val db: FakeDb) : HearingTestRepository {
    override suspend fun save(test: HearingTestEntity, thresholds: List<ThresholdEntity>, trials: List<TrialEntity>, headsetKey: HeadsetKey?): Long =
        error("not used by these screens")

    override fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>> =
        db.tests.map { l -> l.filter { it.profileId == profileId } }

    override fun observeLatest(profileId: Long) = observeForProfile(profileId).map { l -> l.maxByOrNull { it.startedAt } }
    override suspend fun latest(profileId: Long) = db.tests.value.filter { it.profileId == profileId }.maxByOrNull { it.startedAt }
    override suspend fun get(testId: Long) = db.tests.value.firstOrNull { it.id == testId }
    override suspend fun thresholds(testId: Long) = db.thresholds[testId].orEmpty()
    override fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>> = db.tests.map { db.thresholds[testId].orEmpty() }
    override suspend fun trials(testId: Long) = emptyList<TrialEntity>()
    override suspend fun delete(testId: Long) {
        db.tests.value = db.tests.value.filterNot { it.id == testId }
    }

    override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> = observeForProfile(profileId)
}

class FakeSettingsRepo(
    active: Long? = null,
    onboardingDone: Boolean = false,
) : SettingsRepository {
    val active = MutableStateFlow(active)
    val mode = MutableStateFlow(EngineMode.SESSION)
    val boot = MutableStateFlow(false)
    val done = MutableStateFlow(onboardingDone)
    val strength = MutableStateFlow(0.5f)
    val removedProfiles = mutableListOf<Long>()
    val map = MutableStateFlow<Map<String, Long>>(emptyMap())

    override val activeListenerId: Flow<Long?> get() = active
    override val headsetProfiles: Flow<Map<String, Long>> get() = map
    override val engineMode: Flow<EngineMode> get() = mode
    override val startOnBoot: Flow<Boolean> get() = boot
    override val onboardingDone: Flow<Boolean> get() = done
    override val defaultCompStrength: Flow<Float> get() = strength
    override val personalizationEnabled: Flow<Boolean> = MutableStateFlow(true)

    override suspend fun setActiveListenerId(id: Long?) { active.value = id }
    override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) {
        map.value = if (profileId == null) map.value - headsetKey else map.value + (headsetKey to profileId)
    }
    override suspend fun removeProfile(profileId: Long) { removedProfiles += profileId }
    override suspend fun setEngineMode(mode: EngineMode) { this.mode.value = mode }
    override suspend fun setStartOnBoot(enabled: Boolean) { boot.value = enabled }
    override suspend fun setOnboardingDone(done: Boolean) { this.done.value = done }
    override suspend fun setDefaultCompStrength(strength: Float) { this.strength.value = strength }
    override suspend fun setPersonalizationEnabled(enabled: Boolean) = Unit
}
