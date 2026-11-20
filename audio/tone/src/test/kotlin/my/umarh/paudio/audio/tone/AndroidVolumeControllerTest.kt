package my.umarh.paudio.audio.tone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVolumeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun setIndexReturnsReadBackAndNeverThrows() {
        val vc = AndroidVolumeController(context) { AudioDeviceInfo.TYPE_WIRED_HEADSET }
        assertThat(vc.maxIndex).isGreaterThan(0)
        val effective = vc.setIndex(3)
        assertThat(effective).isEqualTo(vc.index())
        assertThat(effective).isEqualTo(3)
        // out-of-range requests are clamped, not thrown
        assertThat(vc.setIndex(10_000)).isEqualTo(vc.maxIndex)
        assertThat(vc.setIndex(-5)).isAtLeast(0)
    }

    @Test
    fun indexDbIsNullOutsideRangeAndNeverThrows() {
        val vc = AndroidVolumeController(context) { AudioDeviceInfo.TYPE_WIRED_HEADSET }
        assertThat(vc.indexDb(vc.maxIndex + 5)).isNull()
        vc.indexDb(vc.maxIndex) // shadow returns an arbitrary value; must not throw
    }

    @Test
    fun audioManagerIsTheStreamMusicSource() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vc = AndroidVolumeController(context) { AudioDeviceInfo.TYPE_WIRED_HEADSET }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        assertThat(vc.index()).isEqualTo(2)
    }
}
