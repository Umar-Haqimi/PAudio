package my.umarh.paudio.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class EngineMode { SESSION, LEGACY }

interface SettingsRepository {
    val activeListenerId: Flow<Long?>
    /** headsetKey -> profileId remembered by auto-switch (F17). */
    val headsetProfiles: Flow<Map<String, Long>>
    val engineMode: Flow<EngineMode>
    val startOnBoot: Flow<Boolean>
    val onboardingDone: Flow<Boolean>
    val defaultCompStrength: Flow<Float>
    val personalizationEnabled: Flow<Boolean>

    suspend fun setActiveListenerId(id: Long?)
    suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?)
    /** Removes every headsetKey -> profile entry pointing at [profileId] (called when a profile is deleted). */
    suspend fun removeProfile(profileId: Long)
    suspend fun setEngineMode(mode: EngineMode)
    suspend fun setStartOnBoot(enabled: Boolean)
    suspend fun setOnboardingDone(done: Boolean)
    suspend fun setDefaultCompStrength(strength: Float)
    suspend fun setPersonalizationEnabled(enabled: Boolean)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {
    private object Keys {
        val activeListener = longPreferencesKey("active_listener_id")
        val headsetProfiles = stringPreferencesKey("headset_profiles_json")
        val engineMode = stringPreferencesKey("engine_mode")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val defaultStrength = floatPreferencesKey("default_comp_strength")
        val personalization = booleanPreferencesKey("personalization_enabled")
    }

    override val activeListenerId = store.data.map { it[Keys.activeListener] }
    override val headsetProfiles = store.data.map { decodeMap(it[Keys.headsetProfiles]) }
    override val engineMode = store.data.map { p ->
        EngineMode.entries.firstOrNull { it.name == p[Keys.engineMode] } ?: EngineMode.SESSION
    }
    override val startOnBoot = store.data.map { it[Keys.startOnBoot] ?: false }
    override val onboardingDone = store.data.map { it[Keys.onboardingDone] ?: false }
    override val defaultCompStrength = store.data.map { (it[Keys.defaultStrength] ?: DEFAULT_STRENGTH).coerceIn(0f, 1f) }
    override val personalizationEnabled = store.data.map { it[Keys.personalization] ?: true }

    override suspend fun setActiveListenerId(id: Long?) {
        store.edit { if (id == null) it.remove(Keys.activeListener) else it[Keys.activeListener] = id }
    }

    override suspend fun setHeadsetProfile(headsetKey: String, profileId: Long?) {
        store.edit {
            val map = decodeMap(it[Keys.headsetProfiles]).toMutableMap()
            if (profileId == null) map.remove(headsetKey) else map[headsetKey] = profileId
            it[Keys.headsetProfiles] = JsonObject(map.mapValues { e -> JsonPrimitive(e.value) }).toString()
        }
    }

    override suspend fun removeProfile(profileId: Long) {
        store.edit {
            val map = decodeMap(it[Keys.headsetProfiles])
            if (map.containsValue(profileId)) {
                it[Keys.headsetProfiles] =
                    JsonObject(map.filterValues { v -> v != profileId }.mapValues { e -> JsonPrimitive(e.value) }).toString()
            }
        }
    }

    override suspend fun setEngineMode(mode: EngineMode) { store.edit { it[Keys.engineMode] = mode.name } }
    override suspend fun setStartOnBoot(enabled: Boolean) { store.edit { it[Keys.startOnBoot] = enabled } }
    override suspend fun setOnboardingDone(done: Boolean) { store.edit { it[Keys.onboardingDone] = done } }
    override suspend fun setDefaultCompStrength(strength: Float) {
        store.edit { it[Keys.defaultStrength] = strength.coerceIn(0f, 1f) }
    }
    override suspend fun setPersonalizationEnabled(enabled: Boolean) { store.edit { it[Keys.personalization] = enabled } }

    private fun decodeMap(text: String?): Map<String, Long> {
        if (text == null) return emptyMap()
        return try {
            (Json.parseToJsonElement(text) as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.longOrNull?.let { k to it }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val DEFAULT_STRENGTH = 0.5f
    }
}
