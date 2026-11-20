package my.umarh.paudio.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.data.repo.ProfileSlot
import my.umarh.paudio.data.repo.ProfileWriteResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private lateinit var env: Env

    @Before fun setUp() { env = Env(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() = env.close()

    @Test fun headsetGetOrCreateIsIdempotent() = runTest {
        val a = env.headsets.getOrCreate(BT)
        val b = env.headsets.getOrCreate(BT)
        assertThat(a).isEqualTo(b)
        assertThat(env.headsets.observeAll().first()).hasSize(1)
    }

    @Test fun anyHeadphonesIsUniquePerListener() = runTest {
        val l = env.listeners.create("A")
        val l2 = env.listeners.create("B")
        val first = env.profiles.ensureAnyHeadphonesProfile(l)
        assertThat(env.profiles.ensureAnyHeadphonesProfile(l)).isEqualTo(first)
        assertThat(env.profiles.create(l, null, "second any")).isEqualTo(ProfileWriteResult.Conflict(first))
        // other listener is independent
        assertThat(env.profiles.create(l2, null, "any")).isInstanceOf(ProfileWriteResult.Ok::class.java)
        assertThat(env.profiles.observeAll().first().first().profiles).hasSize(1)
    }

    @Test fun headsetSlotUniquePerListener() = runTest {
        val l = env.listeners.create("A")
        val h = env.headsets.getOrCreate(WIRED)
        assertThat(env.profiles.create(l, h, "x")).isInstanceOf(ProfileWriteResult.Ok::class.java)
        assertThat(env.profiles.create(l, h, "y")).isInstanceOf(ProfileWriteResult.Conflict::class.java)
    }

    @Test fun resolveForHeadset() = runTest {
        val l = env.listeners.create("A")
        val h = env.headsets.getOrCreate(WIRED)
        val id = (env.profiles.create(l, h, "x") as ProfileWriteResult.Ok).profileId
        env.profiles.ensureAnyHeadphonesProfile(l)
        assertThat(env.profiles.resolveForHeadset(l, WIRED.headsetKey)?.id).isEqualTo(id)
        assertThat(env.profiles.resolveForHeadset(l, BT.headsetKey)).isNull()
    }

    @Test fun moveConflictFailsCleanly() = runTest {
        val l = env.listeners.create("A")
        val h1 = env.headsets.getOrCreate(WIRED)
        val h2 = env.headsets.getOrCreate(BT)
        val p1 = (env.profiles.create(l, h1, "p1") as ProfileWriteResult.Ok).profileId
        val p2 = (env.profiles.create(l, h2, "p2") as ProfileWriteResult.Ok).profileId
        assertThat(env.profiles.moveToHeadset(p1, h2)).isEqualTo(ProfileWriteResult.Conflict(p2))
        assertThat(env.profiles.get(p1)!!.profile.headsetId).isEqualTo(h1)
        // move to Any works once, then conflicts
        assertThat(env.profiles.moveToHeadset(p1, null)).isEqualTo(ProfileWriteResult.Ok(p1))
        assertThat(env.profiles.moveToHeadset(p2, null)).isEqualTo(ProfileWriteResult.Conflict(p1))
        assertThat(env.profiles.moveToHeadset(999, null)).isEqualTo(ProfileWriteResult.NotFound)
    }

    @Test fun cascadeDeletes() = runTest {
        val p = buildRichProfile(env)
        val testId = env.tests.latest(p)!!.id
        env.profiles.delete(p)
        assertThat(env.tests.get(testId)).isNull()
        assertThat(env.tests.thresholds(testId)).isEmpty()
        assertThat(env.tests.trials(testId)).isEmpty()
        assertThat(env.prefs.latest(p)).isNull()
    }

    @Test fun deleteListenerCascades() = runTest {
        val p = buildRichProfile(env)
        val l = env.profiles.get(p)!!.profile.listenerId
        env.listeners.delete(l)
        assertThat(env.profiles.get(p)).isNull()
        assertThat(env.tests.latest(p)).isNull()
    }

    @Test fun duplicateCopiesEverything() = runTest {
        val p = buildRichProfile(env)
        val l = env.profiles.get(p)!!.profile.listenerId
        val h2 = env.headsets.getOrCreate(WIRED)
        // same slot is a conflict
        assertThat(env.profiles.duplicate(p, "copy", ProfileSlot(l, env.profiles.get(p)!!.profile.headsetId)))
            .isEqualTo(ProfileWriteResult.Conflict(p))
        val copy = (env.profiles.duplicate(p, " copy ", ProfileSlot(l, h2)) as ProfileWriteResult.Ok).profileId
        val src = env.profiles.get(p)!!.profile
        val dup = env.profiles.get(copy)!!.profile
        assertThat(dup.name).isEqualTo("copy")
        assertThat(dup.copy(id = src.id, name = src.name, headsetId = src.headsetId, createdAt = src.createdAt, updatedAt = src.updatedAt))
            .isEqualTo(src)
        val srcTests = env.tests.observeForProfile(p).first()
        val dupTests = env.tests.observeForProfile(copy).first()
        assertThat(dupTests).hasSize(2)
        for ((a, b) in srcTests.zip(dupTests)) {
            assertThat(b.copy(id = a.id, profileId = a.profileId)).isEqualTo(a)
            assertThat(env.tests.thresholds(b.id).map { it.copy(testId = 0) })
                .isEqualTo(env.tests.thresholds(a.id).map { it.copy(testId = 0) })
            assertThat(env.tests.trials(b.id).map { it.copy(testId = 0) })
                .isEqualTo(env.tests.trials(a.id).map { it.copy(testId = 0) })
        }
        assertThat(env.prefs.latest(copy)!!.roundsJson).isEqualTo(env.prefs.latest(p)!!.roundsJson)
        // deleting the copy leaves the original intact
        env.profiles.delete(copy)
        assertThat(env.tests.observeForProfile(p).first()).hasSize(2)
    }

    @Test fun testsNewestFirstAndLatest() = runTest {
        val p = buildRichProfile(env)
        val list = env.tests.observeForProfile(p).first()
        assertThat(list.map { it.startedAt }).containsExactly(2_000L, 1_000L).inOrder()
        assertThat(env.tests.latest(p)!!.startedAt).isEqualTo(2_000L)
    }

    @Test fun trendFiltersByHeadsetKeyAfterMove() = runTest {
        val p = buildRichProfile(env) // 2 tests on BT
        val wired = env.headsets.getOrCreate(WIRED)
        env.profiles.moveToHeadset(p, wired)
        env.tests.save(quickTest(p, 5_000, WIRED.headsetKey.value), thresholds())
        env.tests.save(quickTest(p, 6_000, null), thresholds()) // no snapshot key -> current headset (wired)
        assertThat(env.tests.observeForTrend(p, WIRED.headsetKey).first().map { it.startedAt })
            .containsExactly(6_000L, 5_000L).inOrder()
        assertThat(env.tests.observeForTrend(p, BT.headsetKey).first().map { it.startedAt })
            .containsExactly(2_000L, 1_000L).inOrder()
    }

    @Test fun saveTestIsAtomic() = runTest {
        val p = buildRichProfile(env)
        val bad = thresholds() + thresholds().first() // duplicate PK -> insert fails
        runCatching { env.tests.save(quickTest(p, 9_000, null), bad) }
        assertThat(env.tests.observeForProfile(p).first().map { it.startedAt }).doesNotContain(9_000L)
    }

    @Test fun headsetKeyIsRecordedFromDevice() = runTest {
        val id = env.headsets.getOrCreate(OutputDevice(OutputDeviceType.BLUETOOTH_A2DP, " X ", "11:22:33:ab:cd"))
        assertThat(env.headsets.getByKey(HeadsetKey("BLUETOOTH_A2DP|X|AB:CD"))?.id).isEqualTo(id)
    }

    @Test fun updateSettingsDoesNotClobberConcurrentMove() = runTest {
        val l = env.listeners.create("A")
        val h = env.headsets.getOrCreate(WIRED)
        val p = (env.profiles.create(l, h, "p") as ProfileWriteResult.Ok).profileId
        val stale = env.profiles.get(p)!!.profile // what a read-modify-write would have held
        env.profiles.moveToHeadset(p, null)
        env.profiles.updateSettings(p, my.umarh.paudio.data.repo.ProfileSettings(0.9f, 1f, 2f, 3f, false))
        env.profiles.setSeedColor(p, 42, true)
        val now = env.profiles.get(p)!!.profile
        assertThat(stale.headsetId).isEqualTo(h)
        assertThat(now.headsetId).isNull()
        assertThat(now.compStrength).isEqualTo(0.9f)
        assertThat(now.personalizationOn).isFalse()
        assertThat(now.seedColor).isEqualTo(42)
        assertThat(now.seedOverridden).isTrue()
        // the Any slot is still single
        assertThat(env.profiles.create(l, null, "again")).isEqualTo(ProfileWriteResult.Conflict(p))
    }

    @Test fun dbBackstopRejectsSecondAnyProfileAndSchemaValidationTolerates() = runTest {
        val l = env.listeners.create("A")
        env.profiles.ensureAnyHeadphonesProfile(l)
        val sql = env.db.openHelper.writableDatabase
        val r = runCatching {
            sql.execSQL("INSERT INTO profile (listenerId, headsetId, name, compStrength, gB, gP, gT, personalizationOn, seedOverridden, createdAt, updatedAt) VALUES ($l, NULL, 'dup', 0.5, 0, 0, 0, 1, 0, 0, 0)")
        }
        assertThat(r.exceptionOrNull()).isInstanceOf(android.database.sqlite.SQLiteConstraintException::class.java)
    }

    @Test fun backstopSurvivesReopenWithRoomSchemaValidation() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "reopen-test.db"
        ctx.deleteDatabase(name)
        fun open() = androidx.room.Room.databaseBuilder(ctx, my.umarh.paudio.data.db.PAudioDatabase::class.java, name)
            .addCallback(my.umarh.paudio.data.db.AnySlotIndexCallback).allowMainThreadQueries().build()
        val first = open()
        first.openHelper.writableDatabase.close()
        first.close()
        val second = open() // identity-hash validation must accept the extra index
        val c = second.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'idx_profile_any_slot'")
        assertThat(c.count).isEqualTo(1)
        c.close(); second.close(); ctx.deleteDatabase(name)
    }

    @Test fun deleteProfilePrunesSettingsMapping() = runTest {
        val l = env.listeners.create("A")
        val p = env.profiles.ensureAnyHeadphonesProfile(l)
        env.settings.setHeadsetProfile("k", p)
        env.profiles.delete(p)
        assertThat(env.settings.map.value).isEmpty()
    }

    @Test fun saveStampsHeadsetKey() = runTest {
        val p = buildRichProfile(env)
        val id = env.tests.save(quickTest(p, 7_000, null), thresholds(), emptyList(), WIRED.headsetKey)
        assertThat(my.umarh.paudio.data.db.DeviceSnapshot.headsetKeyOf(env.tests.get(id)!!.deviceSnapshotJson))
            .isEqualTo(WIRED.headsetKey.value)
    }
}
