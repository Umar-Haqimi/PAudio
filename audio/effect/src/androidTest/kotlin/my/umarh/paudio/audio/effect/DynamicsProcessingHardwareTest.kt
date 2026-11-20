package my.umarh.paudio.audio.effect

import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.dsp.DpEqPlanner
import my.umarh.paudio.testing.RequiresHardware
import org.junit.Test
import org.junit.runner.RunWith

/** Creates DynamicsProcessing on the test app's own AudioTrack session and reads the config back. */
@RequiresHardware("real DynamicsProcessing effect engine")
@RunWith(AndroidJUnit4::class)
class DynamicsProcessingHardwareTest {
    @Test
    fun createsOnOwnSessionAndReadsBack() {
        val fs = 48_000
        val track = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val cfg = DpEqPlanner.flat(fs).toEqConfig()
            val handle = DynamicsProcessingBackend().create(track.audioSessionId, cfg)
            try {
                assertThat(handle.control.value).isTrue()
                Thread.sleep(500) // worker performs the readback
                assertThat(handle.clamped.value).isFalse()
            } finally {
                handle.release()
            }
        } finally {
            track.release()
        }
    }
}
