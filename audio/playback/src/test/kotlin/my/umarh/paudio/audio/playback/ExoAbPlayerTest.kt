package my.umarh.paudio.audio.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.EffectBackend
import my.umarh.paudio.core.model.EffectHandle
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.testing.FakeBypassController
import my.umarh.paudio.testing.FakeEffectBackend
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class ExoAbPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fakeBackend = FakeEffectBackend()
    private val fakeBypass = FakeBypassController()
    private var grant = true
    private var onLoss: (() -> Unit)? = null
    private val focus = object : AbAudioFocus {
        override fun request(onLoss: () -> Unit): Boolean {
            this@ExoAbPlayerTest.onLoss = onLoss
            return grant
        }

        override fun abandon() = Unit
    }
    private var exo: ExoPlayer? = null
    private val events = mutableListOf<String>()
    private val volumeAtApply = mutableListOf<Float>()

    /** Records player state at the moment the bypass is acquired. */
    private val bypass = object : BypassController by fakeBypass {
        override fun acquire(reason: BypassReason): BypassToken {
            events += "acquire:state=${exo!!.playbackState}:items=${exo!!.mediaItemCount}"
            return fakeBypass.acquire(reason)
        }
    }

    /** Records the player volume at the moment an effect config is applied. */
    private val backend = object : EffectBackend {
        override fun create(session: Int, cfg: EqConfig): EffectHandle {
            val h = fakeBackend.create(session, cfg)
            return object : EffectHandle by h {
                override fun apply(cfg: EqConfig) {
                    volumeAtApply += exo!!.volume
                    h.apply(cfg)
                }
            }
        }
    }
    private val ticks = mutableListOf<ListenTick>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val players = mutableListOf<ExoAbPlayer>()

    private fun newPlayer(): ExoAbPlayer =
        ExoAbPlayer(context, backend, bypass, BypassReason.RESULTS_AB, focus, SESSION) {
            TestExoPlayerBuilder(it).build().also { p -> exo = p }
        }.also {
            it.isAudible = { true }
            scope.launch { it.listenTicks.collect { t -> ticks += t } }
            players += it
        }

    @After fun tearDown() {
        players.forEach { it.release() }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** Runs load from a background thread (so looper misuse would throw) while pumping the main looper. */
    private fun runLoad(p: ExoAbPlayer, src: AudioSource, loop: ClosedRange<kotlin.time.Duration>? = null): Result<Unit> =
        runBlocking {
            val r = CoroutineScope(Dispatchers.Default).async { runCatching { p.load(src, loop) } }
            withTimeout(20_000) {
                while (!r.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
            }
            r.await()
        }

    @Test fun ownSessionSetBeforePrepareAndOneEnabledEffectOnIt() {
        newPlayer()
        assertThat(exo!!.audioSessionId).isEqualTo(SESSION)
        assertThat(exo!!.playbackState).isEqualTo(Player.STATE_IDLE) // nothing prepared yet
        assertThat(fakeBackend.handles).hasSize(1)
        assertThat(fakeBackend.handles[0].session).isEqualTo(SESSION)
        assertThat(fakeBackend.handles[0].enabled).isTrue()
    }

    @Test fun playBeforeSwapToThrows() {
        val p = newPlayer()
        val r = runCatching { p.play() }
        assertThat(r.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(fakeBypass.openCount).isEqualTo(0)
    }

    @Test fun swapToAppliesPreampPlusMatchGainAfterDuckAtVolumeZero() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f)
        volumeAtApply.clear()
        p.play()
        p.swapTo(EqConfig.flat().copy(preampDb = -4f), -6f)
        assertThat(fakeBackend.handles[0].config.preampDb).isEqualTo(0f) // still ducking down
        idle(200)
        assertThat(fakeBackend.handles[0].config.preampDb).isWithin(1e-6f).of(-10f)
        assertThat(volumeAtApply).containsExactly(0f)
        assertThat(exo!!.volume).isEqualTo(1f)
    }

    @Test fun rapidSwapsCoalesceToLastOne() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f)
        p.play()
        p.swapTo(EqConfig.flat(), -1f)
        p.swapTo(EqConfig.flat(), -2f)
        p.swapTo(EqConfig.flat(), -3f)
        idle(200)
        assertThat(fakeBackend.handles[0].appliedConfigs.map { it.preampDb }).containsExactly(0f, 0f, -3f).inOrder()
    }

    @Test fun swapWhilePausedAppliesImmediately() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), -2f)
        assertThat(fakeBackend.handles[0].config.preampDb).isEqualTo(-2f)
    }

    @Test fun bypassAcquiredBeforePrepareHeldUntilRelease() {
        val p = newPlayer()
        runLoad(p, AudioSource.Asset("demo/does-not-exist.flac"))
        assertThat(events).containsExactly("acquire:state=${Player.STATE_IDLE}:items=0")
        assertThat(fakeBypass.acquiredReasons).containsExactly(BypassReason.RESULTS_AB)
        assertThat(fakeBypass.openCount).isEqualTo(1)
        p.release()
        assertThat(fakeBypass.openCount).isEqualTo(0)
        assertThat(fakeBackend.handles[0].released).isTrue()
        p.release() // idempotent
    }

    @Test fun errorKeepsTokenAndBlocksPlay() {
        val p = newPlayer()
        val r = runLoad(p, AudioSource.Asset("demo/does-not-exist.flac"))
        assertThat(r.isFailure).isTrue()
        assertThat(p.error.value).isNotNull()
        assertThat(fakeBypass.openCount).isEqualTo(1)
        p.swapTo(EqConfig.flat(), 0f)
        p.play()
        assertThat(exo!!.playWhenReady).isFalse()
        p.release()
        assertThat(fakeBypass.openCount).isEqualTo(0)
        assertThat(fakeBackend.handles.all { it.released }).isTrue()
    }

    @Test fun focusDeniedIsExposedAndDoesNotPlay() {
        grant = false
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f)
        p.play()
        assertThat(p.focusDenied.value).isTrue()
        assertThat(exo!!.playWhenReady).isFalse()
    }

    @Test fun focusLossPauses() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f)
        p.play()
        assertThat(exo!!.playWhenReady).isTrue()
        onLoss!!.invoke()
        idle(10)
        assertThat(exo!!.playWhenReady).isFalse()
    }

    @Test fun listenTicksAttributedToOldSideBeforeSwapAndSilentWhenPaused() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f, AbSide.A)
        p.play()
        idle(1000)
        p.swapTo(EqConfig.flat(), 0f, AbSide.B)
        idle(1000)
        p.pause()
        val a = ticks.filter { it.side == AbSide.A }.sumOf { it.deltaMs }
        val b = ticks.filter { it.side == AbSide.B }.sumOf { it.deltaMs }
        assertThat(a).isAtLeast(950L)
        assertThat(a).isAtMost(1100L)
        assertThat(b).isAtLeast(900L)
        val total = ticks.sumOf { it.deltaMs }
        idle(2000)
        assertThat(ticks.sumOf { it.deltaMs }).isEqualTo(total)
    }

    @Test fun ticksWithUnknownSideAreNotEmitted() {
        val p = newPlayer()
        p.swapTo(EqConfig.flat(), 0f)
        p.play()
        idle(1000)
        p.pause()
        assertThat(ticks).isEmpty()
    }

    @Test fun loopRegionBecomesClippingMediaSource() {
        val p = newPlayer()
        val withLoop = p.buildMediaSource(AudioSource.Asset("demo/echoplex.flac"), 0.seconds..12.seconds)
        assertThat(withLoop).isInstanceOf(ClippingMediaSource::class.java)
        assertThat(withLoop.mediaItem.localConfiguration!!.uri.toString()).isEqualTo("asset:///demo/echoplex.flac")
        assertThat(p.buildMediaSource(AudioSource.Asset("demo/echoplex.flac"), null))
            .isNotInstanceOf(ClippingMediaSource::class.java)
    }

    private companion object {
        const val SESSION = 4242
    }
}
