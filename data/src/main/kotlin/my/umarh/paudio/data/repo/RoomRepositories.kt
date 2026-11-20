package my.umarh.paudio.data.repo

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.PAudioDatabase
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import my.umarh.paudio.data.settings.SettingsRepository

@Singleton
class RoomListenerRepository @Inject constructor(private val db: PAudioDatabase) : ListenerRepository {
    private val dao get() = db.listenerDao()
    override fun observeAll() = dao.observeAll()
    override fun observe(id: Long) = dao.observe(id)
    override suspend fun get(id: Long) = dao.get(id)
    override suspend fun create(name: String): Long =
        dao.insert(ListenerEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    override suspend fun getOrCreate(name: String): Long = db.withTransaction {
        dao.getByName(name.trim())?.id ?: create(name)
    }

    override suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())
    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class RoomHeadsetRepository @Inject constructor(private val db: PAudioDatabase) : HeadsetRepository {
    private val dao get() = db.headsetDao()
    override fun observeAll() = dao.observeAll()
    override suspend fun getByKey(key: HeadsetKey) = dao.getByKey(key.value)

    override suspend fun getOrCreate(device: OutputDevice, displayName: String): Long =
        getOrCreate(device.headsetKey, displayName, device)

    override suspend fun getOrCreate(key: HeadsetKey, displayName: String, device: OutputDevice): Long =
        db.withTransaction {
            dao.getByKey(key.value)?.id ?: dao.insertIgnore(
                HeadsetEntity(
                    key = key.value,
                    displayName = displayName,
                    type = device.type,
                    productName = device.productName.trim(),
                    addressSuffix = if (device.type.isBluetooth) device.addressSuffix.takeLast(OutputDevice.ADDRESS_SUFFIX_LENGTH).uppercase() else "",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
}

@Singleton
class RoomProfileRepository @Inject constructor(
    private val db: PAudioDatabase,
    private val settings: SettingsRepository,
) : ProfileRepository {
    private val profiles get() = db.profileDao()
    private val tests get() = db.hearingTestDao()
    private val prefs get() = db.preferenceTestDao()

    override fun observeAll(): Flow<List<ListenerProfiles>> =
        combine(db.listenerDao().observeAll(), profiles.observeAll(), db.headsetDao().observeAll()) { ls, ps, hs ->
            val headsets = hs.associateBy { it.id }
            val byListener = ps.groupBy { it.listenerId }
            ls.map { l ->
                ListenerProfiles(l, byListener[l.id].orEmpty().map { ProfileWithHeadset(it, it.headsetId?.let(headsets::get)) })
            }
        }

    override fun observe(profileId: Long): Flow<ProfileWithHeadset?> =
        combine(profiles.observe(profileId), db.headsetDao().observeAll()) { p, hs ->
            p?.let { ProfileWithHeadset(it, hs.firstOrNull { h -> h.id == it.headsetId }) }
        }

    override suspend fun get(profileId: Long): ProfileWithHeadset? {
        val p = profiles.get(profileId) ?: return null
        return ProfileWithHeadset(p, p.headsetId?.let { db.headsetDao().get(it) })
    }

    override suspend fun create(
        listenerId: Long,
        headsetId: Long?,
        name: String,
        settings: ProfileSettings,
    ): ProfileWriteResult = db.withTransaction {
        profiles.getForSlot(listenerId, headsetId)?.let { return@withTransaction ProfileWriteResult.Conflict(it.id) }
        val now = System.currentTimeMillis()
        ProfileWriteResult.Ok(
            profiles.insert(
                ProfileEntity(
                    listenerId = listenerId, headsetId = headsetId, name = name.trim(),
                    compStrength = settings.compStrength, gB = settings.gB, gP = settings.gP, gT = settings.gT,
                    personalizationOn = settings.personalizationOn, createdAt = now, updatedAt = now,
                ),
            ),
        )
    }

    override suspend fun rename(profileId: Long, name: String) =
        profiles.rename(profileId, name.trim(), System.currentTimeMillis())

    // Targeted UPDATEs (no read-modify-write): a concurrent moveToHeadset cannot be overwritten.
    override suspend fun updateSettings(profileId: Long, settings: ProfileSettings) = profiles.updateSettings(
        profileId, settings.compStrength, settings.gB, settings.gP, settings.gT, settings.personalizationOn,
        System.currentTimeMillis(),
    )

    override suspend fun setPersonalization(profileId: Long, on: Boolean) =
        profiles.setPersonalization(profileId, on, System.currentTimeMillis())

    override suspend fun setSeedColor(profileId: Long, seedColor: Int?, overridden: Boolean) =
        profiles.updateSeed(profileId, seedColor, overridden, System.currentTimeMillis())

    override suspend fun delete(profileId: Long) {
        profiles.delete(profileId)
        settings.removeProfile(profileId)
    }

    override suspend fun duplicate(profileId: Long, newName: String, target: ProfileSlot): ProfileWriteResult =
        db.withTransaction {
            val src = profiles.get(profileId) ?: return@withTransaction ProfileWriteResult.NotFound
            profiles.getForSlot(target.listenerId, target.headsetId)
                ?.let { return@withTransaction ProfileWriteResult.Conflict(it.id) }
            val now = System.currentTimeMillis()
            val newId = profiles.insert(
                src.copy(
                    id = 0, listenerId = target.listenerId, headsetId = target.headsetId,
                    name = newName.trim(), createdAt = now, updatedAt = now,
                ),
            )
            for (t in tests.listForProfileAsc(profileId)) {
                val newTest = tests.insertTest(t.copy(id = 0, profileId = newId))
                tests.insertThresholds(tests.thresholds(t.id).map { it.copy(testId = newTest) })
                tests.insertTrials(tests.trials(t.id).map { it.copy(testId = newTest) })
            }
            for (pt in prefs.listForProfileAsc(profileId)) prefs.insert(pt.copy(id = 0, profileId = newId))
            ProfileWriteResult.Ok(newId)
        }

    override suspend fun moveToHeadset(profileId: Long, headsetId: Long?): ProfileWriteResult = db.withTransaction {
        val p = profiles.get(profileId) ?: return@withTransaction ProfileWriteResult.NotFound
        if (p.headsetId == headsetId) return@withTransaction ProfileWriteResult.Ok(profileId)
        profiles.getForSlot(p.listenerId, headsetId)?.let { return@withTransaction ProfileWriteResult.Conflict(it.id) }
        profiles.update(p.copy(headsetId = headsetId, updatedAt = System.currentTimeMillis()))
        ProfileWriteResult.Ok(profileId)
    }

    override suspend fun ensureAnyHeadphonesProfile(listenerId: Long): Long = db.withTransaction {
        profiles.getForSlot(listenerId, null)?.id ?: (create(listenerId, null, ANY_HEADPHONES_NAME) as ProfileWriteResult.Ok).profileId
    }

    override suspend fun resolveForHeadset(listenerId: Long, headsetKey: HeadsetKey): ProfileEntity? =
        profiles.getForHeadsetKey(listenerId, headsetKey.value)

    companion object {
        const val ANY_HEADPHONES_NAME = "Any headphones"
    }
}

@Singleton
class RoomHearingTestRepository @Inject constructor(private val db: PAudioDatabase) : HearingTestRepository {
    private val dao get() = db.hearingTestDao()

    override suspend fun save(
        test: HearingTestEntity,
        thresholds: List<ThresholdEntity>,
        trials: List<TrialEntity>,
        headsetKey: HeadsetKey?,
    ): Long =
        db.withTransaction {
            val snapshot = if (headsetKey != null) {
                DeviceSnapshot.withHeadsetKey(test.deviceSnapshotJson, headsetKey.value)
            } else {
                test.deviceSnapshotJson
            }
            val id = dao.insertTest(test.copy(id = 0, deviceSnapshotJson = snapshot))
            dao.insertThresholds(thresholds.map { it.copy(testId = id) })
            dao.insertTrials(trials.map { it.copy(testId = id) })
            id
        }

    override fun observeForProfile(profileId: Long) = dao.observeForProfile(profileId)
    override fun observeLatest(profileId: Long) = dao.observeLatest(profileId)
    override suspend fun latest(profileId: Long) = dao.latest(profileId)
    override suspend fun get(testId: Long) = dao.get(testId)
    override suspend fun thresholds(testId: Long) = dao.thresholds(testId)
    override fun observeThresholds(testId: Long) = dao.observeThresholds(testId)
    override suspend fun trials(testId: Long) = dao.trials(testId)
    override suspend fun delete(testId: Long) = dao.delete(testId)

    override fun observeForTrend(profileId: Long, headsetKey: HeadsetKey): Flow<List<HearingTestEntity>> =
        combine(dao.observeForProfile(profileId), db.profileDao().observe(profileId), db.headsetDao().observeAll()) { ts, p, hs ->
            val currentKey = hs.firstOrNull { it.id == p?.headsetId }?.key
            ts.filter { (DeviceSnapshot.headsetKeyOf(it.deviceSnapshotJson) ?: currentKey) == headsetKey.value }
        }
}

@Singleton
class RoomPreferenceTestRepository @Inject constructor(private val db: PAudioDatabase) : PreferenceTestRepository {
    private val dao get() = db.preferenceTestDao()
    override suspend fun save(test: PreferenceTestEntity) = dao.insert(test.copy(id = 0))
    override fun observeForProfile(profileId: Long) = dao.observeForProfile(profileId)
    override suspend fun latest(profileId: Long) = dao.latest(profileId)
}
