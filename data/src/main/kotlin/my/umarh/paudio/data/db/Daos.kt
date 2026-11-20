package my.umarh.paudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenerDao {
    @Insert suspend fun insert(listener: ListenerEntity): Long
    @Query("SELECT * FROM listener ORDER BY id") fun observeAll(): Flow<List<ListenerEntity>>
    @Query("SELECT * FROM listener WHERE id = :id") fun observe(id: Long): Flow<ListenerEntity?>
    @Query("SELECT * FROM listener WHERE id = :id") suspend fun get(id: Long): ListenerEntity?
    @Query("SELECT * FROM listener WHERE name = :name ORDER BY id LIMIT 1") suspend fun getByName(name: String): ListenerEntity?
    @Query("UPDATE listener SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM listener WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface HeadsetDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(headset: HeadsetEntity): Long
    @Query("SELECT * FROM headset WHERE `key` = :key") suspend fun getByKey(key: String): HeadsetEntity?
    @Query("SELECT * FROM headset WHERE id = :id") suspend fun get(id: Long): HeadsetEntity?
    @Query("SELECT * FROM headset ORDER BY id") fun observeAll(): Flow<List<HeadsetEntity>>
}

@Dao
interface ProfileDao {
    @Insert suspend fun insert(profile: ProfileEntity): Long
    @Update suspend fun update(profile: ProfileEntity)
    @Query("DELETE FROM profile WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM profile WHERE id = :id") suspend fun get(id: Long): ProfileEntity?
    @Query("SELECT * FROM profile WHERE id = :id") fun observe(id: Long): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile ORDER BY listenerId, id") fun observeAll(): Flow<List<ProfileEntity>>

    /** `IS` compares NULL correctly (Any headphones). */
    @Query("SELECT * FROM profile WHERE listenerId = :listenerId AND headsetId IS :headsetId")
    suspend fun getForSlot(listenerId: Long, headsetId: Long?): ProfileEntity?

    @Query(
        "SELECT p.* FROM profile p JOIN headset h ON p.headsetId = h.id " +
            "WHERE p.listenerId = :listenerId AND h.`key` = :key",
    )
    suspend fun getForHeadsetKey(listenerId: Long, key: String): ProfileEntity?

    @Query("UPDATE profile SET personalizationOn = :on, updatedAt = :now WHERE id = :id")
    suspend fun setPersonalization(id: Long, on: Boolean, now: Long)

    @Query(
        "UPDATE profile SET compStrength = :compStrength, gB = :gB, gP = :gP, gT = :gT, " +
            "personalizationOn = :personalizationOn, updatedAt = :now WHERE id = :id",
    )
    suspend fun updateSettings(
        id: Long, compStrength: Float, gB: Float, gP: Float, gT: Float, personalizationOn: Boolean, now: Long,
    )

    @Query("UPDATE profile SET seedColor = :seedColor, seedOverridden = :overridden, updatedAt = :now WHERE id = :id")
    suspend fun updateSeed(id: Long, seedColor: Int?, overridden: Boolean, now: Long)

    @Query("UPDATE profile SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)
}

@Dao
interface HearingTestDao {
    @Insert suspend fun insertTest(test: HearingTestEntity): Long
    @Insert suspend fun insertThresholds(items: List<ThresholdEntity>)
    @Insert suspend fun insertTrials(items: List<TrialEntity>)
    @Query("SELECT * FROM hearing_test WHERE id = :id") suspend fun get(id: Long): HearingTestEntity?

    @Query("SELECT * FROM hearing_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC")
    fun observeForProfile(profileId: Long): Flow<List<HearingTestEntity>>

    @Query("SELECT * FROM hearing_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC")
    suspend fun listForProfile(profileId: Long): List<HearingTestEntity>

    @Query("SELECT * FROM hearing_test WHERE profileId = :profileId ORDER BY startedAt ASC, id ASC")
    suspend fun listForProfileAsc(profileId: Long): List<HearingTestEntity>

    @Query("SELECT * FROM hearing_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<HearingTestEntity?>

    @Query("SELECT * FROM hearing_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun latest(profileId: Long): HearingTestEntity?

    @Query("SELECT * FROM threshold WHERE testId = :testId ORDER BY ear, freqHz")
    suspend fun thresholds(testId: Long): List<ThresholdEntity>

    @Query("SELECT * FROM threshold WHERE testId = :testId ORDER BY ear, freqHz")
    fun observeThresholds(testId: Long): Flow<List<ThresholdEntity>>

    @Query("SELECT * FROM trial WHERE testId = :testId ORDER BY seq")
    suspend fun trials(testId: Long): List<TrialEntity>

    @Query("DELETE FROM hearing_test WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface PreferenceTestDao {
    @Insert suspend fun insert(test: PreferenceTestEntity): Long

    @Query("SELECT * FROM preference_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC")
    fun observeForProfile(profileId: Long): Flow<List<PreferenceTestEntity>>

    @Query("SELECT * FROM preference_test WHERE profileId = :profileId ORDER BY startedAt ASC, id ASC")
    suspend fun listForProfileAsc(profileId: Long): List<PreferenceTestEntity>

    @Query("SELECT * FROM preference_test WHERE profileId = :profileId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun latest(profileId: Long): PreferenceTestEntity?
}
