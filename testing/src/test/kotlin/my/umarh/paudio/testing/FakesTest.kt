package my.umarh.paudio.testing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.StereoBuffer
import org.junit.Test

class FakesTest {
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
}
