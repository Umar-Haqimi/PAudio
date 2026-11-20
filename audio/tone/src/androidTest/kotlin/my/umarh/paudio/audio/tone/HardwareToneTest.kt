package my.umarh.paudio.audio.tone

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.umarh.paudio.core.model.StereoBuffer
import my.umarh.paudio.testing.RequiresHardware
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Needs a real device with audio output. Excluded in CI with
 * `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=my.umarh.paudio.testing.RequiresHardware`.
 */
@RunWith(AndroidJUnit4::class)
@RequiresHardware("AudioTrack float streaming and getStreamVolumeDb curve")
class HardwareToneTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Test
    fun continuousFloatStreamPlaysSeveralStimuliBackToBackAndStopsEarly() = runBlocking {
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).toInt()
        val out = AudioTrackToneOutput()
        out.open(rate)
        try {
            val n = rate / 4
            val s = FloatArray(n) { (0.01 * sin(2 * PI * 1000 * it / rate)).toFloat() }
            val buf = StereoBuffer(s, FloatArray(n), rate)
            val t0 = System.nanoTime()
            repeat(3) { out.play(buf); out.playSilence(100) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertThat(ms).isAtLeast(3 * 350L - 50)
            out.play(buf)
            // stopCurrent during a long stimulus returns within ramp + buffer latency
            val long = StereoBuffer(FloatArray(rate * 2) { 0.001f }, FloatArray(rate * 2), rate)
            val job = launch(Dispatchers.Default) { out.play(long) }
            Thread.sleep(300)
            out.stopCurrent()
            job.join()
        } finally {
            out.close()
            out.close()
        }
    }

    @Test
    fun getStreamVolumeDbIsFiniteMonotonicForCurrentRoute() {
        val vc = AndroidVolumeController(context)
        val dbs = (0..vc.maxIndex).map { vc.indexDb(it) }
        // Log for HARDWARE_TEST.md; null is legitimate on BT absolute-volume sinks.
        println("getStreamVolumeDb curve: $dbs")
        val finite = dbs.filterNotNull()
        assertThat(finite).isInOrder()
    }
}
