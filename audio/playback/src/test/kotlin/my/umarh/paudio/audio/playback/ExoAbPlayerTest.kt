package my.umarh.paudio.audio.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.testing.FakeBypassController
import my.umarh.paudio.testing.FakeEffectBackend
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExoAbPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeEffectBackend()
    private val bypass = FakeBypassController()
    private val focus = object : AbAudioFocus {
        override fun request(onLoss: () -> Unit) = true
        override fun abandon() = Unit
    }
    private var exo: ExoPlayer? = null

    private fun newPlayer(): ExoAbPlayer =
        ExoAbPlayer(context, backend, bypass, BypassReason.RESULTS_AB, focus, SESSION) {
            TestExoPlayerBuilder(it).build().also { p -> exo = p }
        }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun ownSessionSetBeforePrepareAndOneEnabledEffectOnIt() {
        val p = newPlayer()
        assertThat(exo!!.audioSessionId).isEqualTo(SESSION)
        assertThat(exo!!.playbackState).isEqualTo(Player.STATE_IDLE) // nothing prepared yet
        assertThat(backend.handles).hasSize(1)
        assertThat(backend.handles[0].session).isEqualTo(SESSION)
        assertThat(backend.handles[0].enabled).isTrue()
        p.release()
    }

    @Test fun swapToAppliesPreampPlusMatchGainAfterDuck() {
        val p = newPlayer()
        p.play()
        p.swapTo(EqConfig.flat().copy(preampDb = -4f), -6f)
        assertThat(backend.handles[0].config.preampDb).isEqualTo(0f) // still ducking down
        idle(200)
        assertThat(backend.handles[0].config.preampDb).isWithin(1e-6f).of(-10f)
        assertThat(exo!!.volume).isEqualTo(1f)
        p.release()
    }

    @Test fun rapidSwapsCoalesceToLastOne() {
        val p = newPlayer()
        p.play()
        p.swapTo(EqConfig.flat(), -1f)
        p.swapTo(EqConfig.flat(), -2f)
        p.swapTo(EqConfig.flat(), -3f)
        idle(200)
        assertThat(backend.handles[0].appliedConfigs.map { it.preampDb }).containsExactly(0f, -3f).inOrder()
        p.release()
    }

    @Test fun swapWhilePausedAppliesImmediately() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), -2f)
        assertThat(backend.handles[0].config.preampDb).isEqualTo(-2f)
        p.release()
    }

    @Test fun bypassHeldWhilePlayingAndClosedOnRelease() {
        val p = newPlayer()
        assertThat(bypass.openCount).isEqualTo(0)
        p.play()
        assertThat(bypass.openCount).isEqualTo(1)
        assertThat(bypass.acquiredReasons).containsExactly(BypassReason.RESULTS_AB)
        p.pause()
        assertThat(bypass.openCount).isEqualTo(1)
        p.release()
        assertThat(bypass.openCount).isEqualTo(0)
        assertThat(backend.handles[0].released).isTrue()
        p.release() // idempotent
    }

    @Test fun releaseCleansUpEvenWhenLoadFailed() {
        val p = newPlayer()
        p.play()
        val result = CompletableDeferred<Result<Unit>>()
        runBlocking {
            launch(Dispatchers.Unconfined) {
                result.complete(runCatching { p.load(AudioSource.Asset("demo/does-not-exist.flac"), null) })
            }
            withTimeout(20_000) {
                while (!result.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
            }
        }
        assertThat(result.getCompleted().isFailure).isTrue()
        p.release()
        assertThat(bypass.openCount).isEqualTo(0)
        assertThat(backend.handles.all { it.released }).isTrue()
    }

    private companion object {
        const val SESSION = 4242
    }
}
