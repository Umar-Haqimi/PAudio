package my.umarh.paudio.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * Profile JSON schema v1 (SPEC §1 interop requirement, §5.6). Export -> import must reproduce the profile
 * exactly: raw thresholds and trials are stored, derived curves are NOT (they are recomputed).
 * Unknown fields are ignored; an unknown major version is rejected.
 * Field names mirror the Room entities of SPEC §5.6 (ids are not exported).
 */

/** Top-level document. */
@Serializable
data class ProfileFile(
    val schemaVersion: Int = ProfileJson.SCHEMA_VERSION,
    val listener: ListenerJson,
    /** null = profile for "Any headphones". */
    val headset: HeadsetJson? = null,
    val profile: ProfileSettingsJson,
    val tests: List<HearingTestJson> = emptyList(),
    val preferenceTests: List<PreferenceTestJson> = emptyList(),
)

@Serializable
data class ListenerJson(val name: String, val createdAt: Long = 0L)

@Serializable
data class HeadsetJson(
    val key: String,
    val displayName: String,
    val type: OutputDeviceType,
    val productName: String,
    val addressSuffix: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class ProfileSettingsJson(
    val name: String,
    /** Compensation strength s, 0..1 (default 0.5). */
    val compStrength: Float = 0.5f,
    /** Preference gains in dB: bass shelf, presence peak, treble shelf. */
    val gB: Float = 0f,
    val gP: Float = 0f,
    val gT: Float = 0f,
    val personalizationOn: Boolean = true,
    /** ARGB seed colour; null = not computed yet. */
    val seedColor: Int? = null,
    val seedOverridden: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class HearingTestJson(
    val mode: TestMode,
    val startedAt: Long,
    val durationMs: Long,
    val volIndex: Int,
    val volMax: Int,
    /** 1 kHz binaural threshold at the locked volume (dBFS). */
    val lRef: Float,
    val deviceSnapshotJson: String? = null,
    val ancOn: Boolean? = null,
    val appVersion: String,
    val algoVersion: Int,
    /** Catch-trial false-positive rate (Quick mode). */
    val fpRate: Float? = null,
    val reliable: Boolean = true,
    val thresholds: List<ThresholdJson>,
    /** Raw trial log; optional. */
    val trials: List<TrialJson>? = null,
)

@Serializable
data class ThresholdJson(
    val ear: Ear,
    val freqHz: Int,
    /** dBFS; null when no level was determined. For censored points this is the bound ("≥ level"). */
    val levelDbfs: Float?,
    val censored: Boolean = false,
    val lowConfidence: Boolean = false,
    val trials: Int = 0,
)

@Serializable
data class TrialJson(
    val seq: Int,
    val ear: Ear,
    val freqHz: Int,
    val levelDbfs: Float,
    val isCatch: Boolean = false,
    /** true = "heard". */
    val response: Boolean,
    /** Milliseconds since test start. */
    val tMs: Long,
)

@Serializable
data class PreferenceTestJson(
    val startedAt: Long,
    val gB: Float,
    val gP: Float,
    val gT: Float,
    /** Free-form per-round log (schema owned by :core:preference). */
    val rounds: JsonElement? = null,
)

class UnsupportedSchemaException(message: String) : SerializationException(message)

/** Encode/decode entry points for [ProfileFile]. */
object ProfileJson {
    const val SCHEMA_VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prettyJson: Json = Json(json) { prettyPrint = true }

    fun encode(file: ProfileFile, pretty: Boolean = false): String =
        (if (pretty) prettyJson else json).encodeToString(ProfileFile.serializer(), file)

    /**
     * @throws UnsupportedSchemaException if `schemaVersion` is missing or not [SCHEMA_VERSION].
     * @throws SerializationException on any other malformed input.
     */
    fun decode(text: String): ProfileFile {
        val root: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Not a JSON object", e)
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw UnsupportedSchemaException("Missing or invalid schemaVersion")
        if (version != SCHEMA_VERSION) throw UnsupportedSchemaException("Unsupported schemaVersion $version")
        return json.decodeFromJsonElement(ProfileFile.serializer(), root)
    }
}
