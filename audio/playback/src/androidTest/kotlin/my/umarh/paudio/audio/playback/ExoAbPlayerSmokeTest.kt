package my.umarh.paudio.audio.playback

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.testing.FakeBypassController
import my.umarh.paudio.testing.FakeEffectBackend
import my.umarh.paudio.testing.RequiresHardware
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Plays a bundled demo clip on a real device: audible output is not asserted, only that playback runs. */
@RequiresHardware
@RunWith(AndroidJUnit4::class)
class ExoAbPlayerSmokeTest {
    @Test fun playsDemoClipLoop() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = FakeEffectBackend()
        val bypass = FakeBypassController()
        val done = CountDownLatch(1)
        var playing = false
        Handler(Looper.getMainLooper()).post {
            val clip = DemoClipSource.preference(DemoClip.ECHOPLEX)
            val p = ExoAbPlayer(ctx, backend, bypass, BypassReason.PREFERENCE_AB)
            runBlocking { p.load(clip.source, clip.loop) }
            p.swapTo(EqConfig.flat(), -1f)
            p.play()
            Handler(Looper.getMainLooper()).postDelayed({
                playing = p.playing.value
                p.release()
                done.countDown()
            }, 2_500)
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        assertThat(playing).isTrue()
        assertThat(bypass.openCount).isEqualTo(0)
    }
}
