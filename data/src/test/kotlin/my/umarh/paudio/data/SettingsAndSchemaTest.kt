package my.umarh.paudio.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.umarh.paudio.data.db.PAudioDatabase
import my.umarh.paudio.data.settings.DataStoreSettingsRepository
import my.umarh.paudio.data.settings.EngineMode
import org.junit.Test

class SettingsTest {
    @Test fun defaultsAndSetters() = runTest {
        val file = File.createTempFile("settings", ".preferences_pb").also { it.delete() }
        val scope = TestScope(UnconfinedTestDispatcher())
        val repo = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file })
        assertThat(repo.activeListenerId.first()).isNull()
        assertThat(repo.engineMode.first()).isEqualTo(EngineMode.SESSION)
        assertThat(repo.startOnBoot.first()).isFalse()
        assertThat(repo.onboardingDone.first()).isFalse()
        assertThat(repo.defaultCompStrength.first()).isEqualTo(0.5f)
        assertThat(repo.personalizationEnabled.first()).isTrue()
        repo.setActiveListenerId(3)
        repo.setEngineMode(EngineMode.LEGACY)
        repo.setStartOnBoot(true)
        repo.setOnboardingDone(true)
        repo.setDefaultCompStrength(7f)
        repo.setHeadsetProfile("WIRED|x|", 9)
        repo.setHeadsetProfile("BT|y|AB:CD", 10)
        repo.setHeadsetProfile("WIRED|x|", null)
        assertThat(repo.activeListenerId.first()).isEqualTo(3L)
        assertThat(repo.engineMode.first()).isEqualTo(EngineMode.LEGACY)
        assertThat(repo.startOnBoot.first()).isTrue()
        assertThat(repo.onboardingDone.first()).isTrue()
        assertThat(repo.defaultCompStrength.first()).isEqualTo(1f)
        assertThat(repo.headsetProfiles.first()).containsExactly("BT|y|AB:CD", 10L)
        file.delete()
    }
}

/** Guards the exported schema; copy `N.json` to `N+1.json` workflow is covered by the androidTest MigrationTest. */
class SchemaFileTest {
    private val dir = File("schemas/${PAudioDatabase::class.java.name}")

    @Test fun schemaV1ExistsAndMatchesEntities() {
        val f = File(dir, "${PAudioDatabase.VERSION}.json")
        assertThat(f.exists()).isTrue()
        val db = Json.parseToJsonElement(f.readText()).jsonObject["database"]!!.jsonObject
        assertThat(db["version"]!!.jsonPrimitive.content.toInt()).isEqualTo(PAudioDatabase.VERSION)
        val tables = db["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        assertThat(tables).containsExactly(
            "listener", "headset", "profile", "hearing_test", "threshold", "trial", "preference_test",
        )
    }

    @Test fun everyVersionUpToCurrentHasASchemaFile() {
        for (v in 1..PAudioDatabase.VERSION) assertThat(File(dir, "$v.json").exists()).isTrue()
    }
}
