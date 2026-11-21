package my.umarh.paudio.audio.device

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidChecksRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun environmentChecksReadHiddenSettings() = runTest {
        Settings.System.putInt(context.contentResolver, "master_mono", 1)
        Settings.System.putFloat(context.contentResolver, "master_balance", -0.4f)
        val snap = AndroidEnvironmentChecks(context).snapshot()
        assertThat(snap.mono).isTrue()
        assertThat(snap.balance).isWithin(0.001f).of(-0.4f)
        assertThat(snap.dndOn).isNotEqualTo(true)
        assertThat(EnvironmentVerdict.of(snap).isBlocking).isTrue()
    }

    @Test
    fun unsetSettingsMeanDefaults() = runTest {
        val snap = AndroidEnvironmentChecks(context).snapshot()
        assertThat(snap.mono).isFalse()
        assertThat(snap.balance).isEqualTo(0f)
    }

    @Test
    fun samsungMonoAudioSettingAlsoCountsAsMono() = runTest {
        Settings.System.putInt(context.contentResolver, "mono_audio", 1)
        assertThat(AndroidEnvironmentChecks(context).snapshot().mono).isTrue()
        Settings.System.putInt(context.contentResolver, "mono_audio", 0)
        assertThat(AndroidEnvironmentChecks(context).snapshot().mono).isFalse()
    }

    @Test
    fun noiseMeterWithoutPermissionIsEmptyAndDoesNotCrash() = runTest {
        assertThat(AndroidNoiseMeter(context).levels().toList()).isEmpty()
    }

    @Test
    fun outputDevicesDoesNotCrashAtConstruction() {
        AndroidOutputDevices(context).current.value // null or speaker depending on the shadow
    }
}
