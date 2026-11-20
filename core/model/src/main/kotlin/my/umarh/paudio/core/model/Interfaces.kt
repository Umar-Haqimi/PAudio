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
    /**
     * Creates a DISABLED effect configured with [cfg].
     * @throws EffectCreationException when the effect can't be created (conflict with another EQ app or refusal).
     */
    fun create(session: Int, cfg: EqConfig): EffectHandle
}

/** Why [EffectBackend.create] failed (DECISIONS §3 conflict detection (a)). */
enum class EffectFailureKind {
    /** Another effect with ≥ priority owns the engine (`UnsupportedOperationException` from the constructor). */
    CONFLICT,

    /** The audio system refused the effect (also after the MBC-passthrough retry). */
    CREATION_FAILED,
}

class EffectCreationException(val kind: EffectFailureKind, message: String? = null, cause: Throwable? = null) :
    RuntimeException(message ?: kind.name, cause)

interface EffectHandle {
    fun apply(cfg: EqConfig)
    fun setEnabled(on: Boolean)

    /** true while this handle has control of the underlying effect (SPEC §2.2 trap 15). */
    val control: StateFlow<Boolean>

    /**
     * true when the read-back after the last full apply showed the HAL clamped/altered the band layout
     * (DECISIONS §1 fallback ladder): the engine then moves down the ladder. Default: never.
     */
    val clamped: StateFlow<Boolean> get() = NeverClamped

    /** Idempotent. */
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

private val NeverClamped: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
