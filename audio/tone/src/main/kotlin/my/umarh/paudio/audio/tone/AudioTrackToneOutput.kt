package my.umarh.paudio.audio.tone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.core.model.InterruptibleToneOutput
import my.umarh.paudio.core.model.StereoBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** AudioTrack-backed [PcmSink]: float PCM when supported, otherwise 16-bit with TPDF dither. */
internal class AudioTrackSink private constructor(
    private val track: AudioTrack,
    val isFloat: Boolean,
) : PcmSink {
    private val shorts = ShortArray(if (isFloat) 0 else 8192)

    override fun write(interleaved: FloatArray, frames: Int): Int {
        val samples = frames * 2
        if (isFloat) {
            val n = track.write(interleaved, 0, samples, AudioTrack.WRITE_BLOCKING)
            return if (n < 0) n else n / 2
        }
        var done = 0
        while (done < samples) {
            val chunk = minOf(shorts.size, samples - done)
            Pcm16.convert(if (done == 0) interleaved else interleaved.copyOfRange(done, done + chunk), shorts, chunk)
            val n = track.write(shorts, 0, chunk, AudioTrack.WRITE_BLOCKING)
            if (n < 0) return n
            done += n
        }
        return frames
    }

    override val playedFrames: Long
        get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    override fun start() = track.play()

    override fun stop() {
        try { track.pause(); track.flush() } catch (_: IllegalStateException) { }
        try { track.stop() } catch (_: IllegalStateException) { }
    }

    override fun release() = track.release()

    companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /** Tries float first, then 16-bit; null if neither initialises. */
        fun create(sampleRate: Int): AudioTrackSink? {
            for (float in booleanArrayOf(true, false)) {
                val enc = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
                val mask = AudioFormat.CHANNEL_OUT_STEREO
                val min = AudioTrack.getMinBufferSize(sampleRate, mask, enc)
                if (min <= 0) continue
                val track = try {
                    AudioTrack.Builder()
                        .setAudioAttributes(MEDIA_ATTRIBUTES)
                        .setAudioFormat(
                            AudioFormat.Builder().setEncoding(enc).setSampleRate(sampleRate).setChannelMask(mask).build(),
                        )
                        .setBufferSizeInBytes(min * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (_: Exception) {
                    continue
                }
                if (track.state == AudioTrack.STATE_INITIALIZED) return AudioTrackSink(track, float)
                track.release()
            }
            return null
        }
    }
}

/**
 * [InterruptibleToneOutput] over ONE continuous stereo AudioTrack for the whole session (SPEC §2.2 trap 5):
 * the writer thread (URGENT_AUDIO) writes stimulus or digital silence back to back, so the stream never stops
 * or underruns between stimuli (no per-stimulus start/stop clicks or route wake-ups).
 *
 * USAGE_MEDIA/CONTENT_TYPE_MUSIC so STREAM_MUSIC volume applies. [open] may be called again after [close].
 * Playout is judged by the playback head, not by the write position.
 */
@Singleton
class AudioTrackToneOutput internal constructor(
    private val sinkFactory: (Int) -> PcmSink?,
    private val threadInit: () -> Unit = {},
) : InterruptibleToneOutput {
    @Inject constructor() : this(
        { AudioTrackSink.create(it) },
        { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) },
    )

    private val lock = Any()
    private var engine: StreamEngine? = null

    // One flow for the lifetime of this object: collectors never bind to a stale engine.
    private val _playing = MutableStateFlow(false)

    override val playing: StateFlow<Boolean> get() = _playing

    override suspend fun open(sampleRate: Int) {
        synchronized(lock) {
            val e = engine
            // A failed engine reports isClosed, so it is rebuilt here.
            if (e != null && !e.isClosed && e.sampleRate == sampleRate) return
            e?.close()
            _playing.value = false
            val sink = sinkFactory(sampleRate) ?: error("Could not create AudioTrack at $sampleRate Hz")
            engine = StreamEngine(
                sink, sampleRate,
                threadInit = threadInit,
                sinkFactory = { sinkFactory(sampleRate) },
                playingFlow = _playing,
            ).also { it.start() }
        }
    }

    private fun requireEngine(): StreamEngine =
        synchronized(lock) { engine?.takeIf { !it.isClosed } } ?: error("ToneOutput not open")

    override suspend fun play(stimulus: StereoBuffer) = requireEngine().play(stimulus)

    /**
     * Digital silence is already flowing; this plays an explicit silent segment so timing stays sample-based. It
     * never sets [playing], which means "an audible stimulus is under the playback head" (the core contract).
     */
    override suspend fun playSilence(ms: Int) = requireEngine().playSilence(ms)

    override suspend fun stopCurrent() {
        synchronized(lock) { engine }?.stopCurrent()
    }

    /** Idempotent; safe in finally blocks. */
    override fun close() {
        val e = synchronized(lock) { engine.also { engine = null } }
        e?.close()
        _playing.value = false
    }
}
