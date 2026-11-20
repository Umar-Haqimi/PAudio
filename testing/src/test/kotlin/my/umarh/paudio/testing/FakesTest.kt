package my.umarh.paudio.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.model.EqConfig
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.model.SessionEvent
import my.umarh.paudio.core.model.StereoBuffer
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakesTest {
    @Test
    fun toneOutputIsAudibleOnlyDuringPlayAndStopCurrentIsHarmless() = runTest {
        val out = FakeToneOutput()
        out.open(48_000)
        val seen = mutableListOf<Boolean>()
        out.onPlay = { seen += out.playing.value }
        out.stopCurrent()
        out.play(StereoBuffer(FloatArray(4), FloatArray(4), 48_000))
        assertThat(seen).containsExactly(true)
        assertThat(out.playing.value).isFalse()
    }

    @Test
    fun toneOutputRecordsBuffersAndSilence() = runTest {
        val out = FakeToneOutput()
        out.open(48_000)
        out.play(StereoBuffer(FloatArray(4), FloatArray(4), 48_000))
        out.playSilence(100)
        out.close()
        assertThat(out.played).hasSize(1)
        assertThat(out.silenceMs).isEqualTo(100)
        assertThat(out.isOpen).isFalse()
    }

    @Test
    fun effectBackendTracksEnabledHandles() {
        val backend = FakeEffectBackend()
        val h = backend.create(42, EqConfig.flat())
        assertThat(backend.anyEnabled).isFalse()
        h.setEnabled(true)
        assertThat(backend.anyEnabled).isTrue()
        h.release()
        assertThat(backend.anyEnabled).isFalse()
    }

    @Test
    fun volumeControllerClampsToSafeLimit() {
        val v = FakeVolumeController(maxIndex = 15, initialIndex = 5).apply { safeLimit = 10 }
        assertThat(v.setIndex(14)).isEqualTo(10)
        assertThat(v.indexDb(15)).isEqualTo(0f)
    }

    @Test
    fun volumeChangesEmitsExternalChangesOnly() = runTest(UnconfinedTestDispatcher()) {
        val v = FakeVolumeController()
        val seen = mutableListOf<Int>()
        val job = launch { v.changes.collect { seen += it } }
        v.setIndex(3)
        v.externalChange(9)
        assertThat(seen).containsExactly(9)
        assertThat(v.index()).isEqualTo(9)
        job.cancel()
    }

    @Test
    fun effectHandleWithoutControlIsNotActive() {
        val backend = FakeEffectBackend()
        val h = backend.create(1, EqConfig.flat()) as FakeEffectHandle
        h.setEnabled(true)
        h.setControl(false)
        assertThat(backend.activeEnabled).isEmpty()
        assertThat(backend.enabledRequested).hasSize(1)
    }

    @Test
    fun noiseMeterAndSessionSourceEmit() = runTest(UnconfinedTestDispatcher()) {
        val meter = FakeNoiseMeter()
        meter.levels().test {
            meter.emit(-40f)
            assertThat(awaitItem()).isEqualTo(-40f)
        }
        val sessions = FakeSessionSource()
        sessions.events.test {
            sessions.open(7, "com.example.player")
            assertThat(awaitItem()).isEqualTo(SessionEvent.Open(7, "com.example.player"))
        }
    }

    @Test
    fun abPlayerRecordsCalls() = runTest {
        val p = FakeAbPlayer()
        p.load(AudioSource.Asset("clips/discipline.flac"), 1.seconds..5.seconds)
        p.play()
        p.swapTo(EqConfig.flat(), -3f)
        assertThat(p.isPlaying).isTrue()
        assertThat(p.lastSwap!!.inputGainDb).isEqualTo(-3f)
        p.release()
        assertThat(p.isPlaying).isFalse()
    }
}
