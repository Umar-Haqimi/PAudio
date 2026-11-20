package my.umarh.paudio.data.transfer

import androidx.room.withTransaction
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import my.umarh.paudio.core.model.HeadsetJson
import my.umarh.paudio.core.model.HearingTestJson
import my.umarh.paudio.core.model.ListenerJson
import my.umarh.paudio.core.model.PreferenceTestJson
import my.umarh.paudio.core.model.ProfileFile
import my.umarh.paudio.core.model.ProfileJson
import my.umarh.paudio.core.model.ProfileSettingsJson
import my.umarh.paudio.core.model.ThresholdJson
import my.umarh.paudio.core.model.TrialJson
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HeadsetEntity
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ListenerEntity
import my.umarh.paudio.data.db.PAudioDatabase
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity

/** Exports one profile as schema-v1 JSON with deterministic ordering (tests by startedAt/id, thresholds by ear/freq, trials by seq). */
@Singleton
class ProfileExporter @Inject constructor(private val db: PAudioDatabase) {

    suspend fun export(profileId: Long): String? = db.withTransaction {
        val p = db.profileDao().get(profileId) ?: return@withTransaction null
        val listener = db.listenerDao().get(p.listenerId) ?: return@withTransaction null
        val headset = p.headsetId?.let { db.headsetDao().get(it) }
        val tests = db.hearingTestDao().listForProfileAsc(profileId).map { t ->
            HearingTestJson(
                mode = t.mode, startedAt = t.startedAt, durationMs = t.durationMs, volIndex = t.volIndex,
                volMax = t.volMax, lRef = t.lRef, deviceSnapshotJson = DeviceSnapshot.parse(t.deviceSnapshotJson),
                ancOn = t.ancOn, appVersion = t.appVersion, algoVersion = t.algoVersion, fpRate = t.fpRate,
                reliable = t.reliable,
                thresholds = db.hearingTestDao().thresholds(t.id)
                    .sortedWith(compareBy({ it.ear.ordinal }, { it.freqHz }))
                    .map { ThresholdJson(it.ear, it.freqHz, it.levelDbfs, it.censored, it.lowConfidence, it.trials) },
                trials = db.hearingTestDao().trials(t.id).sortedBy { it.seq }
                    .map { TrialJson(it.seq, it.ear, it.freqHz, it.levelDbfs, it.isCatch, it.response, it.run, it.tMs) },
            )
        }
        val prefs = db.preferenceTestDao().listForProfileAsc(profileId).map {
            PreferenceTestJson(it.startedAt, it.gB, it.gP, it.gT, DeviceSnapshot.parse(it.roundsJson))
        }
        ProfileJson.encode(
            ProfileFile(
                listener = ListenerJson(listener.name, listener.createdAt),
                headset = headset?.let {
                    HeadsetJson(it.key, it.displayName, it.type, it.productName, it.addressSuffix, it.createdAt)
                },
                profile = ProfileSettingsJson(
                    name = p.name, compStrength = p.compStrength, gB = p.gB, gP = p.gP, gT = p.gT,
                    personalizationOn = p.personalizationOn, seedColor = p.seedColor,
                    seedOverridden = p.seedOverridden, createdAt = p.createdAt, updatedAt = p.updatedAt,
                ),
                tests = tests,
                preferenceTests = prefs,
            ),
        )
    }

    suspend fun exportBytes(profileId: Long): ByteArray? = export(profileId)?.toByteArray(Charsets.UTF_8)
}

sealed interface ImportResult {
    data class Success(val profileId: Long) : ImportResult

    /**
     * The target slot already holds [existingProfileId]. Nothing was written. The UI resolves it by calling
     * import again with [ConflictPolicy.REPLACE] (overwrite that profile), [ConflictPolicy.IMPORT_AS_ANY]
     * (use the listener's "Any headphones" slot instead) or [ConflictPolicy.REPLACE_ANY].
     */
    data class Conflict(
        val existingProfileId: Long,
        val existingProfileName: String,
        /** True when the conflicting slot is "Any headphones" (headset == null). */
        val anySlot: Boolean,
    ) : ImportResult

    data class Error(val reason: ImportErrorReason, val detail: String) : ImportResult
}

enum class ConflictPolicy {
    /** Default: report [ImportResult.Conflict] if the (listener, headset) slot is taken. */
    ASK,

    /** Delete the profile in the target slot (with its tests) and import in its place. */
    REPLACE,

    /** Import into the listener's "Any headphones" slot (Conflict if that is taken too). */
    IMPORT_AS_ANY,

    /** Like [IMPORT_AS_ANY] but replaces an existing Any-headphones profile. */
    REPLACE_ANY,
}

/**
 * Imports UNTRUSTED JSON. The listener is matched by (trimmed) name, else created; the headset by recomputed
 * key, else created. Never throws on malformed input.
 */
@Singleton
class ProfileImporter @Inject constructor(private val db: PAudioDatabase) {

    suspend fun import(input: InputStream, policy: ConflictPolicy = ConflictPolicy.ASK): ImportResult {
        val bytes = try {
            input.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= ProfileImportValidator.MAX_BYTES) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            return ImportResult.Error(ImportErrorReason.IO, e.javaClass.simpleName)
        }
        return import(bytes, policy)
    }

    suspend fun import(bytes: ByteArray, policy: ConflictPolicy = ConflictPolicy.ASK): ImportResult =
        when (val v = ProfileImportValidator.validate(bytes)) {
            is ValidationResult.Invalid -> ImportResult.Error(v.reason, v.detail)
            is ValidationResult.Valid -> try {
                store(v.file, policy)
            } catch (c: ConflictSignal) {
                c.result
            } catch (e: Exception) {
                ImportResult.Error(ImportErrorReason.STORAGE, e.javaClass.simpleName)
            }
        }

    private suspend fun store(f: ProfileFile, policy: ConflictPolicy): ImportResult = db.withTransaction {
        val listenerDao = db.listenerDao()
        val profileDao = db.profileDao()
        val now = System.currentTimeMillis()
        val listenerId = listenerDao.getByName(f.listener.name)?.id
            ?: listenerDao.insert(ListenerEntity(name = f.listener.name, createdAt = f.listener.createdAt.takeIf { it > 0 } ?: now))
        val headsetId = if (policy == ConflictPolicy.IMPORT_AS_ANY || policy == ConflictPolicy.REPLACE_ANY) {
            null
        } else {
            f.headset?.let { h ->
                db.headsetDao().getByKey(h.key)?.id ?: db.headsetDao().insertIgnore(
                    HeadsetEntity(
                        key = h.key, displayName = h.displayName, type = h.type, productName = h.productName,
                        addressSuffix = h.addressSuffix, createdAt = h.createdAt,
                    ),
                )
            }
        }
        val existing = profileDao.getForSlot(listenerId, headsetId)
        if (existing != null) {
            val replace = policy == ConflictPolicy.REPLACE || policy == ConflictPolicy.REPLACE_ANY
            if (!replace) {
                // Roll back the optimistic listener/headset inserts: this was only a probe.
                throw ConflictSignal(ImportResult.Conflict(existing.id, existing.name, headsetId == null))
            }
            profileDao.delete(existing.id)
        }
        val s = f.profile
        val profileId = profileDao.insert(
            ProfileEntity(
                listenerId = listenerId, headsetId = headsetId, name = s.name, compStrength = s.compStrength,
                gB = s.gB, gP = s.gP, gT = s.gT, personalizationOn = s.personalizationOn, seedColor = s.seedColor,
                seedOverridden = s.seedOverridden, createdAt = s.createdAt, updatedAt = s.updatedAt,
            ),
        )
        val tests = db.hearingTestDao()
        for (t in f.tests) {
            val id = tests.insertTest(
                HearingTestEntity(
                    profileId = profileId, mode = t.mode, startedAt = t.startedAt, durationMs = t.durationMs,
                    volIndex = t.volIndex, volMax = t.volMax, lRef = t.lRef,
                    deviceSnapshotJson = t.deviceSnapshotJson?.toString(), ancOn = t.ancOn,
                    appVersion = t.appVersion, algoVersion = t.algoVersion, fpRate = t.fpRate, reliable = t.reliable,
                ),
            )
            tests.insertThresholds(
                t.thresholds.map { ThresholdEntity(id, it.ear, it.freqHz, it.levelDbfs, it.censored, it.lowConfidence, it.trials) },
            )
            tests.insertTrials(
                t.trials.orEmpty().map { TrialEntity(id, it.seq, it.ear, it.freqHz, it.levelDbfs, it.isCatch, it.response, it.tMs, it.run) },
            )
        }
        for (p in f.preferenceTests) {
            db.preferenceTestDao().insert(
                PreferenceTestEntity(
                    profileId = profileId, startedAt = p.startedAt, gB = p.gB, gP = p.gP, gT = p.gT,
                    roundsJson = (p.rounds as JsonElement?)?.toString(),
                ),
            )
        }
        ImportResult.Success(profileId)
    }
}

/** Aborts (rolls back) the import transaction to report a conflict without side effects. */
private class ConflictSignal(val result: ImportResult.Conflict) : RuntimeException(null, null, false, false)
