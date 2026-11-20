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
