package my.umarh.paudio.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Helpers for `HearingTest.deviceSnapshotJson`, which records the headset key at test time. */
object DeviceSnapshot {
    const val KEY_FIELD = "headsetKey"

    /** Adds/overwrites `headsetKey` in [existingJson] (a JSON object; anything else starts a new object). */
    fun withHeadsetKey(existingJson: String?, headsetKey: String): String {
        val base = parse(existingJson) as? JsonObject
        val map = LinkedHashMap<String, JsonElement>(base ?: emptyMap())
        map[KEY_FIELD] = JsonPrimitive(headsetKey)
        return JsonObject(map).toString()
    }

    fun headsetKeyOf(json: String?): String? =
        ((parse(json) as? JsonObject)?.get(KEY_FIELD) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun parse(json: String?): JsonElement? =
        if (json == null) null else try { Json.parseToJsonElement(json) } catch (e: Exception) { null }
}
