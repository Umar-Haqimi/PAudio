package my.umarh.paudio.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

// Interfaces that decouple hardware from logic (SPEC §5.2). Fakes live in `:testing`.

interface ToneOutput {
    suspend fun open(sampleRate: Int)
    suspend fun play(stimulus: StereoBuffer)
    suspend fun playSilence(ms: Int)
    fun close()
}

/**
 * Extension of [ToneOutput] (SPEC §5.2 stays intact) for tests where the listener answers *during* a stimulus.
 */
interface InterruptibleToneOutput : ToneOutput {
    /** True only while a stimulus is audible at the transducer (playout position inside the stimulus). */
    val playing: StateFlow<Boolean>

    /**
     * Stops the stimulus currently playing with a short (25 ms) raised-cosine ramp-down and returns once the
     * ramp has been played out. The stream continues with digital silence. No-op if nothing is playing.
     * The pending [play] call returns normally.
     */
    suspend fun stopCurrent()
}

interface VolumeController {
    val maxIndex: Int
    fun index(): Int
    fun setIndex(i: Int): Int
    fun indexDb(i: Int): Float?
    val changes: Flow<Int>
}

interface OutputDevices {
    val current: StateFlow<OutputDevice?>
}

interface EnvironmentChecks {
    suspend fun snapshot(): EnvSnapshot
}

interface NoiseMeter {
    /** dBFS RMS; never persisted. */
    fun levels(): Flow<Float>
}

interface EffectBackend {
    fun create(session: Int, cfg: EqConfig): EffectHandle
}

interface EffectHandle {
    fun apply(cfg: EqConfig)
    fun setEnabled(on: Boolean)

    /** true while this handle has control of the underlying effect (SPEC §2.2 trap 15). */
    val control: StateFlow<Boolean>
    fun release()
}

interface SessionSource {
    val events: Flow<SessionEvent>
}

interface AbPlayer {
    suspend fun load(src: AudioSource, loop: ClosedRange<Duration>?)
    fun swapTo(cfg: EqConfig, inputGainDb: Float)
    fun play()
    fun pause()
    fun release()
}
