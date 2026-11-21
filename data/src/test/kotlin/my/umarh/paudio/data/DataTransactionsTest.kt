package my.umarh.paudio.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.data.db.PreferenceTestEntity
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.tx.RoomDataTransactions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataTransactionsTest {
    private lateinit var env: Env

    @Before fun setUp() { env = Env(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() = env.close()

    private fun prefTest(profileId: Long) =
        PreferenceTestEntity(profileId = profileId, startedAt = 1L, gB = 1f, gP = 2f, gT = 3f, roundsJson = "[]")

    @Test fun committedTogether() = runTest {
        val tx = RoomDataTransactions(env.db)
        val p = env.profiles.ensureAnyHeadphonesProfile(env.listeners.create("A"))
        val r = tx.withTransaction {
            env.prefs.save(prefTest(p))
            env.profiles.updateSettings(p, ProfileSettings(gB = 1f, gP = 2f, gT = 3f))
            "ok"
        }
        assertThat(r).isEqualTo("ok")
        assertThat(env.prefs.latest(p)).isNotNull()
        assertThat(env.profiles.get(p)!!.profile.gP).isEqualTo(2f)
    }

    @Test fun rolledBackWhenBlockThrows() = runTest {
        val tx = RoomDataTransactions(env.db)
        val p = env.profiles.ensureAnyHeadphonesProfile(env.listeners.create("A"))
        try {
            tx.withTransaction {
                env.prefs.save(prefTest(p))
                env.profiles.updateSettings(p, ProfileSettings(gB = 1f, gP = 2f, gT = 3f))
                error("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertThat(env.prefs.latest(p)).isNull()
        assertThat(env.profiles.get(p)!!.profile.gP).isEqualTo(0f)
        assertThat(env.prefs.observeForProfile(p).first()).isEmpty()
    }
}
