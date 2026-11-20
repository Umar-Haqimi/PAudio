package my.umarh.paudio.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportErrorReason
import my.umarh.paudio.data.transfer.ImportResult
import my.umarh.paudio.data.transfer.ProfileExporter
import my.umarh.paudio.data.transfer.ProfileImporter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransferTest {
    private lateinit var a: Env
    private lateinit var b: Env

    @Before fun setUp() {
        a = Env(ApplicationProvider.getApplicationContext())
        b = Env(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() { a.close(); b.close() }

    @Test fun roundTripIntoFreshDatabaseIsLossless() = runTest {
        val p = buildRichProfile(a)
        val json1 = ProfileExporter(a.db).export(p)!!
        val res = ProfileImporter(b.db, b.settings).import(json1.toByteArray())
        assertThat(res).isInstanceOf(ImportResult.Success::class.java)
        val json2 = ProfileExporter(b.db).export((res as ImportResult.Success).profileId)!!
        assertThat(json2).isEqualTo(json1)
        // raw values
        val tests = b.tests.observeForProfile(res.profileId).first().reversed()
        assertThat(tests).hasSize(2)
        assertThat(b.tests.thresholds(tests[0].id).map { it.copy(testId = 0) }.toSet())
            .isEqualTo(thresholds().toSet())
        assertThat(b.tests.trials(tests[0].id).map { it.copy(testId = 0) }).isEqualTo(quickTrials())
        assertThat(b.tests.trials(tests[1].id).map { it.run }).containsExactly("DESC", "ASC").inOrder()
        assertThat(b.tests.trials(tests[0].id)[1].levelDbfs).isNull()
        assertThat(b.prefs.latest(res.profileId)!!.gB).isEqualTo(3.5f)
        assertThat(json1).contains("\"schemaVersion\":1")
    }

    @Test fun anyHeadphonesProfileRoundTrips() = runTest {
        val l = a.listeners.create("Zed")
        val p = a.profiles.ensureAnyHeadphonesProfile(l)
        val j1 = ProfileExporter(a.db).export(p)!!
        val r = ProfileImporter(b.db, b.settings).import(j1.toByteArray()) as ImportResult.Success
        assertThat(ProfileExporter(b.db).export(r.profileId)).isEqualTo(j1)
    }

    @Test fun conflictThenResolve() = runTest {
        val p = buildRichProfile(a)
        val json = ProfileExporter(a.db).export(p)!!.toByteArray()
        val importer = ProfileImporter(a.db, a.settings)
        val c = importer.import(json) as ImportResult.Conflict
        assertThat(c.existingProfileId).isEqualTo(p)
        assertThat(c.anySlot).isFalse()
        // nothing written
        assertThat(a.profiles.observeAll().first().single().profiles).hasSize(1)

        val asAny = importer.import(json, ConflictPolicy.IMPORT_AS_ANY) as ImportResult.Success
        assertThat(a.profiles.get(asAny.profileId)!!.profile.headsetId).isNull()
        val c2 = importer.import(json, ConflictPolicy.IMPORT_AS_ANY) as ImportResult.Conflict
        assertThat(c2.anySlot).isTrue()

        val replaced = importer.import(json, ConflictPolicy.REPLACE) as ImportResult.Success
        assertThat(a.profiles.get(p)).isNull()
        assertThat(a.tests.observeForProfile(replaced.profileId).first()).hasSize(2)
        assertThat(a.profiles.observeAll().first().single().profiles).hasSize(2)
    }

    @Test fun listenerMatchedByName() = runTest {
        val p = buildRichProfile(a)
        val json = ProfileExporter(a.db).export(p)!!.toByteArray()
        b.listeners.create("Alice")
        ProfileImporter(b.db, b.settings).import(json)
        assertThat(b.listeners.observeAll().first()).hasSize(1)
    }

    @Test fun malformedInputNeverThrows() = runTest {
        val imp = ProfileImporter(b.db, b.settings)
        for (bad in listOf("", "{", "[]", "null", "{\"schemaVersion\":2}", "{\"schemaVersion\":1}")) {
            assertThat(imp.import(bad.toByteArray())).isInstanceOf(ImportResult.Error::class.java)
        }
        val big = ImportResult.Error(ImportErrorReason.TOO_LARGE, "")
        assertThat(imp.import(ByteArray(2 * 1024 * 1024 + 1)) as ImportResult.Error).isEqualTo(
            ImportResult.Error(ImportErrorReason.TOO_LARGE, "${2 * 1024 * 1024 + 1} bytes"),
        )
        assertThat(big.reason).isEqualTo(ImportErrorReason.TOO_LARGE)
        assertThat(b.listeners.observeAll().first()).isEmpty()
    }

    @Test fun oversizedStreamRejected() = runTest {
        val r = ProfileImporter(b.db, b.settings).import(java.io.ByteArrayInputStream(ByteArray(3 * 1024 * 1024)))
        assertThat((r as ImportResult.Error).reason).isEqualTo(ImportErrorReason.TOO_LARGE)
    }

    @Test fun createdProfileNotFoundExport() = runTest {
        assertThat(ProfileExporter(a.db).export(12345)).isNull()
    }

    @Test fun replaceImportPrunesSettingsMapping() = runTest {
        val p = buildRichProfile(a)
        a.settings.setHeadsetProfile("k", p)
        val json = ProfileExporter(a.db).export(p)!!.toByteArray()
        val r = ProfileImporter(a.db, a.settings).import(json, ConflictPolicy.REPLACE) as ImportResult.Success
        assertThat(r.replacedProfileId).isEqualTo(p)
        assertThat(a.settings.map.value).isEmpty()
    }

    @Test fun truncationDoesNotSplitSurrogatePairs() {
        val name = "x".repeat(99) + "\uD83D\uDE00" // emoji straddles the 100-char limit
        val f = FuzzSupport.validFile().let { it.copy(profile = it.profile.copy(name = name)) }
        val v = my.umarh.paudio.data.transfer.ProfileImportValidator
            .validate(my.umarh.paudio.core.model.ProfileJson.encode(f).toByteArray())
            as my.umarh.paudio.data.transfer.ValidationResult.Valid
        assertThat(v.file.profile.name).isEqualTo("x".repeat(99))
    }
}
