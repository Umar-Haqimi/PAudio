package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity

/** Wall and monotonic time, injectable so tests can run in virtual time. */
interface Clock {
    /** Epoch milliseconds (stored as `startedAt`). */
    fun wallMs(): Long

    /** Monotonic milliseconds (durations, calibration reuse window). */
    fun monotonicMs(): Long
}

/** Audio focus (calls, other media) as the test session needs it. Implemented over `AudioFocusMonitor`. */
interface FocusSource {
    /** Requests focus; false when refused (e.g. a call is active). */
    fun request(): Boolean

    /** Drops the held request (even after a loss) and asks again. */
    fun reacquire(): Boolean

    /** Idempotent; called from `finally`. */
    fun abandon()

    /** Focus changes since [request] (a change before collection is not lost). */
    val events: Flow<FocusEvent>
}

/** Small Android facts the session needs that are not already behind a `:core:model` interface. */
interface TestPlatform {
    /** Native mixer rate (`PROPERTY_OUTPUT_SAMPLE_RATE`); the tone stream is opened at this rate. */
    fun outputSampleRate(): Int

    /** Lowest STREAM_MUSIC index (`getStreamMinVolume`); calibration never plans below it. */
    fun minVolumeIndex(): Int

    fun appVersion(): String

    fun defaultListenerName(): String

    fun defaultProfileName(headsetName: String): String
}

/** Creates the dedicated single-thread dispatcher the test engines run on (SPEC §5.3). */
fun interface SessionDispatcherFactory {
    fun create(): CoroutineDispatcher
}

/**
 * Process-wide gate: at most ONE [TestSession] owns the tone output, the volume lock, audio focus and a bypass token
 * at a time. A new session waits here until the previous one has finished its cleanup (`finally`), so two sessions
 * can never share the singleton tone output / volume controller / focus monitor (DECISIONS §27).
 */
@javax.inject.Singleton
class SessionGate @javax.inject.Inject constructor() {
    internal val mutex = Mutex()
}

/** Whether a test can be recorded for the current output / requested profile (blocking warning otherwise). */
sealed interface TargetCheck {
    data object Ok : TargetCheck

    /** Speaker, earpiece, HDMI, unknown or no output: never tested (SPEC §5.5). */
    data object NotProcessable : TargetCheck

    /** The requested profile is bound to another headset than the connected one. */
    data class WrongHeadset(val displayName: String) : TargetCheck

    data object ProfileMissing : TargetCheck
}

/** Everything persisted for one finished test (the profile is resolved by [TestStore.save]). */
class SaveRequest(
    val requestedProfileId: Long?,
    val device: OutputDevice,
    val test: HearingTestEntity,
    val thresholds: List<ThresholdEntity>,
    val trials: List<TrialEntity>,
) {
    /** The same results for the active listener's profile of [device] (an explicit user choice after a deleted profile). */
    fun withoutRequestedProfile(): SaveRequest = SaveRequest(null, device, test, thresholds, trials)
}

/** [TestStore.save] was asked for a profile that no longer exists (deleted during the test). Never saved elsewhere silently. */
class ProfileMissingException : RuntimeException("requested profile no longer exists")

/** Profile resolution and persistence of a finished test. */
interface TestStore {
    suspend fun check(device: OutputDevice?, requestedProfileId: Long?): TargetCheck

    /**
     * The listener the test will be saved for: the requested profile's listener, else the active listener; null
     * when no listener exists yet (one is created at save time). Keys the calibration cache.
     */
    suspend fun listenerFor(requestedProfileId: Long?): Long?

    /**
     * Resolves/creates listener + headset + profile, then saves. Returns the test id. Throws
     * [ProfileMissingException] when [SaveRequest.requestedProfileId] no longer exists.
     */
    suspend fun save(request: SaveRequest): Long
}

/** A calibration that may be reused (same listener and headset, still within [CalibrationCache.REUSE_WINDOW_MS]). */
data class CachedCalibration(
    val headsetKey: String,
    /** The listener it was measured for (null: no listener existed yet). */
    val listenerId: Long?,
    val index: Int,
    val maxIndex: Int,
    val lRefDbfs: Double,
    val warning: Boolean,
    val atMonotonicMs: Long,
)

/**
 * Process-wide calibration reuse (SPEC F4: once per session, reused within 30 min for the same headset + index).
 * Keyed by listener too: another person's `L_ref` is never reused (DECISIONS §27).
 */
@javax.inject.Singleton
class CalibrationCache @javax.inject.Inject constructor() {
    private var entry: CachedCalibration? = null

    @Synchronized
    fun put(value: CachedCalibration) {
        entry = value
    }

    @Synchronized
    fun get(headsetKey: String, listenerId: Long?, maxIndex: Int, nowMonotonicMs: Long): CachedCalibration? =
        entry?.takeIf {
            it.headsetKey == headsetKey && it.listenerId == listenerId && it.maxIndex == maxIndex &&
                nowMonotonicMs - it.atMonotonicMs in 0..REUSE_WINDOW_MS
        }

    @Synchronized
    fun clear() {
        entry = null
    }

    companion object {
        const val REUSE_WINDOW_MS = 30L * 60L * 1000L
    }
}
