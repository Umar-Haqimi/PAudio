package my.umarh.paudio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.TestMode

/*
 * Room schema v1 (SPEC §5.6). Enums are persisted by name (Room's native enum support).
 * Derived curves are never stored.
 */

@Entity(tableName = "listener")
data class ListenerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "headset", indices = [Index(value = ["key"], unique = true)])
data class HeadsetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val displayName: String,
    val type: OutputDeviceType,
    val productName: String,
    val addressSuffix: String,
    val createdAt: Long,
)

/**
 * `headsetId == null` means "Any headphones". SQLite treats NULLs as distinct in UNIQUE indices, so
 * "one Any-headphones profile per listener" is enforced in [my.umarh.paudio.data.repo.ProfileRepository]
 * inside a transaction (all profile inserts/moves go through it).
 */
@Entity(
    tableName = "profile",
    foreignKeys = [
        ForeignKey(ListenerEntity::class, ["id"], ["listenerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(HeadsetEntity::class, ["id"], ["headsetId"], onDelete = ForeignKey.NO_ACTION),
    ],
    indices = [
        Index(value = ["listenerId", "headsetId"], unique = true),
        Index(value = ["headsetId"]),
    ],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listenerId: Long,
    val headsetId: Long?,
    val name: String,
    val compStrength: Float = 0.5f,
    val gB: Float = 0f,
    val gP: Float = 0f,
    val gT: Float = 0f,
    val personalizationOn: Boolean = true,
    val seedColor: Int? = null,
    val seedOverridden: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "hearing_test",
    foreignKeys = [ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["profileId"])],
)
data class HearingTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val mode: TestMode,
    val startedAt: Long,
    val durationMs: Long,
    val volIndex: Int,
    val volMax: Int,
    val lRef: Float,
    /** JSON text; contains `headsetKey` (see [DeviceSnapshot]) so trends survive profile moves. */
    val deviceSnapshotJson: String?,
    val ancOn: Boolean?,
    val appVersion: String,
    val algoVersion: Int,
    val fpRate: Float?,
    val reliable: Boolean,
)

@Entity(
    tableName = "threshold",
    primaryKeys = ["testId", "ear", "freqHz"],
    foreignKeys = [ForeignKey(HearingTestEntity::class, ["id"], ["testId"], onDelete = ForeignKey.CASCADE)],
)
data class ThresholdEntity(
    val testId: Long,
    val ear: Ear,
    val freqHz: Int,
    val levelDbfs: Float?,
    val censored: Boolean,
    val lowConfidence: Boolean,
    val trials: Int,
)

@Entity(
    tableName = "trial",
    primaryKeys = ["testId", "seq"],
    foreignKeys = [ForeignKey(HearingTestEntity::class, ["id"], ["testId"], onDelete = ForeignKey.CASCADE)],
)
data class TrialEntity(
    val testId: Long,
    val seq: Int,
    val ear: Ear,
    val freqHz: Int,
    /** null = catch trial. */
    val levelDbfs: Float?,
    val isCatch: Boolean,
    val response: Boolean,
    val tMs: Long,
    /** "DESC" / "ASC" (Thorough) or null. */
    val run: String?,
)

@Entity(
    tableName = "preference_test",
    foreignKeys = [ForeignKey(ProfileEntity::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["profileId"])],
)
data class PreferenceTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val startedAt: Long,
    val gB: Float,
    val gP: Float,
    val gT: Float,
    val roundsJson: String?,
)
