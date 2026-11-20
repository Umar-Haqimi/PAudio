package my.umarh.paudio.audio.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import my.umarh.paudio.core.model.NoiseMeter
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.sqrt

/** Coarse ambient noise class from an UNCALIBRATED dBFS reading. */
enum class NoiseLevel {
    QUIET,
    OK,
    TOO_NOISY,

    /** No usable reading (muted / privacy-toggled mic, NaN). */
    UNKNOWN,
    ;

    companion object {
        /** Below this (dBFS) the room is considered quiet. Phone mic AGC/gain differ: thresholds are heuristic. */
        const val QUIET_BELOW_DBFS = -60f

        /** At or above this the room is too noisy for threshold audiometry (uncalibrated heuristic). */
        const val NOISY_FROM_DBFS = -45f

        fun classify(dbfs: Float): NoiseLevel = when {
            dbfs.isNaN() -> UNKNOWN
            dbfs < QUIET_BELOW_DBFS -> QUIET
            dbfs < NOISY_FROM_DBFS -> OK
            else -> TOO_NOISY
        }
    }
}

object NoiseMath {
    const val FLOOR_DBFS = -120f

    /** True if the first [n] samples are all exactly zero: a muted / privacy-blocked mic, not a quiet room. */
    fun isAllZero(samples: ShortArray, n: Int = samples.size): Boolean {
        for (i in 0 until n) if (samples[i].toInt() != 0) return false
        return true
    }

    /** RMS of the first [n] samples of 16-bit PCM in dBFS (full scale sine = -3 dBFS), floored at [FLOOR_DBFS]. */
    fun rmsDbfs(samples: ShortArray, n: Int = samples.size): Float {
        if (n <= 0) return FLOOR_DBFS
        var acc = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            acc += v * v
        }
        val rms = sqrt(acc / n)
        return if (rms <= 0.0) FLOOR_DBFS else maxOf(FLOOR_DBFS, (20.0 * log10(rms)).toFloat())
    }
}

/**
 * Cold flow of RMS levels (dBFS) every ~100 ms. The microphone is open only while collected; samples are only
 * reduced to a number in memory, never stored, logged or persisted (SPEC §6). Without RECORD_AUDIO, or if the
 * mic cannot be opened (in use by a call), the flow is empty.
 */
class AndroidNoiseMeter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : NoiseMeter {
    @Inject constructor(@ApplicationContext context: Context) : this(context, Dispatchers.IO)

    override fun levels(): Flow<Float> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyFlow()
        }
        return flow {
            val record = openRecord() ?: return@flow
            try {
                val chunk = record.sampleRate / 10
                val buf = ShortArray(chunk)
                if (!startQuietly(record)) return@flow
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!readChunk(record, buf, chunk)) return@flow
                    // All-zero = muted / privacy-toggled mic: not a measurement, so emit nothing.
                    if (NoiseMath.isAllZero(buf, chunk)) continue
                    emit(NoiseMath.rmsDbfs(buf, chunk))
                }
            } finally {
                try { record.stop() } catch (_: IllegalStateException) { }
                record.release()
            }
        }.flowOn(ioDispatcher)
    }

    private fun startQuietly(record: AudioRecord): Boolean = try {
        record.startRecording()
        record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (_: IllegalStateException) {
        false
    }

    /** False when the mic was taken away or errored; the flow then ends quietly. */
    private fun readChunk(record: AudioRecord, buf: ShortArray, chunk: Int): Boolean = try {
        var got = 0
        while (got < chunk) {
            val n = record.read(buf, got, chunk - got)
            if (n < 0) return false
            got += n
        }
        true
    } catch (_: IllegalStateException) {
        false
    }

    @Suppress("MissingPermission")
    private fun openRecord(): AudioRecord? {
        for (rate in RATES) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            try {
                val r = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 10 * 2) * 2,
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: SecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }

    private companion object {
        val RATES = intArrayOf(48_000, 44_100, 16_000)
    }
}
