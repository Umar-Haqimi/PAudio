package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.core.audiometry.Iso389_7
import my.umarh.paudio.core.audiometry.PointKey
import my.umarh.paudio.core.audiometry.SimulatedListener
import my.umarh.paudio.core.audiometry.Trial
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.core.model.InterruptibleToneOutput
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.StereoBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

/** Ordered log shared by the doubles so tests can assert the ORDER of acquire / open / close / release. */
class EventLog {
    val events: MutableList<String> = CopyOnWriteArrayList()

    fun add(e: String) {
        events += e
    }

    fun indexOf(e: String): Int = events.indexOf(e)
}

/** Reference-counted bypass that logs and exposes whether a token is open (the P0 assertion hook). */
class TraceBypass(private val log: EventLog = EventLog()) : BypassController {
    private val open = AtomicInteger(0)
    val acquired = AtomicInteger(0)
    private val _bypassed = MutableStateFlow(false)
    override val bypassed: StateFlow<Boolean> = _bypassed
    val openCount: Int get() = open.get()
    val isHeld: Boolean get() = open.get() > 0

    override fun acquire(reason: BypassReason): BypassToken {
        acquired.incrementAndGet()
        open.incrementAndGet()
        _bypassed.value = true
        log.add("bypass.acquire")
        return object : BypassToken {
            private val closed = AtomicBoolean(false)
            override val reason = reason
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    log.add("bypass.release")
                    if (open.decrementAndGet() == 0) _bypassed.value = false
                }
            }
        }
    }
}

/** A tone double the [Rig]/[Brain] can drive: a hook on every stimulus start plus the P0 / lifecycle counters. */
interface HookedTone : InterruptibleToneOutput {
    var onPlay: (StereoBuffer) -> Unit
    val violations: MutableList<String>
    val played: MutableList<StereoBuffer>
    val isOpen: Boolean
    val openCount: AtomicInteger
    val closeCount: AtomicInteger
}

/**
 * [InterruptibleToneOutput] double. Asserts P0 on every call (`violations`), records what was played and, when
 * [timed], takes the stimulus' duration in (virtual or real) time so a cancellation can hit it mid-stimulus.
 * [onPlay] runs when a stimulus starts (non-suspending): the simulated listener answers from there.
 */
class ScriptedTone(
    private val bypassHeld: () -> Boolean,
    private val log: EventLog = EventLog(),
    private val timed: Boolean = false,
) : HookedTone {
    override val violations: MutableList<String> = CopyOnWriteArrayList()
    override val played: MutableList<StereoBuffer> = CopyOnWriteArrayList()
    override val openCount = AtomicInteger(0)
    override val closeCount = AtomicInteger(0)

    /** Extra P0 guard evaluated on every call (e.g. "no enabled effect handle exists"). */
    @Volatile var guard: () -> String? = { null }

    @Volatile override var isOpen = false
        private set
    @Volatile var sampleRate = 0
        private set
    @Volatile var failNextPlayWithStreamLoss = false
    @Volatile var failAllPlays = false

    @Volatile override var onPlay: (StereoBuffer) -> Unit = {}

    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing
    @Volatile private var stopSignal: CompletableDeferred<Unit>? = null

    private fun requireBypass(what: String) {
        if (!bypassHeld()) violations += "$what without a bypass token"
        guard()?.let { violations += "$what: $it" }
    }

    override suspend fun open(sampleRate: Int) {
        requireBypass("open")
        this.sampleRate = sampleRate
        isOpen = true
        openCount.incrementAndGet()
        log.add("tone.open")
    }

    override suspend fun play(stimulus: StereoBuffer) {
        check(isOpen) { "ToneOutput not open" }
        requireBypass("play")
        if (failNextPlayWithStreamLoss || failAllPlays) {
            failNextPlayWithStreamLoss = false
            throw IllegalStateException("stream restarted; stimulus lost")
        }
        played += stimulus
        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        _playing.value = true
        try {
            onPlay(stimulus)
            if (timed) {
                val ms = stimulus.frames * 1000L / stimulus.sampleRate
                withTimeoutOrNull(ms) { stop.await() }
            } else {
                kotlinx.coroutines.yield() // an instant double must still be a cancellation point
            }
        } finally {
            _playing.value = false
            if (stopSignal === stop) stopSignal = null
        }
    }

    override suspend fun playSilence(ms: Int) {
        check(isOpen) { "ToneOutput not open" }
        requireBypass("playSilence")
        if (timed) delay(ms.toLong()) else kotlinx.coroutines.yield()
    }

    override suspend fun stopCurrent() {
        stopSignal?.complete(Unit)
        _playing.value = false
    }

    override fun close() {
        if (isOpen) log.add("tone.close")
        isOpen = false
        closeCount.incrementAndGet()
        _playing.value = false
    }
}

class FakeFocus : FocusSource {
    val abandoned = AtomicInteger(0)
    val requests = AtomicInteger(0)
    @Volatile var grant = true
    private val flow = MutableSharedFlow<FocusEvent>(extraBufferCapacity = 16)

    override fun request(): Boolean {
        requests.incrementAndGet()
        return grant
    }

    override fun reacquire(): Boolean = request()

    override fun abandon() {
        abandoned.incrementAndGet()
    }

    override val events: kotlinx.coroutines.flow.Flow<FocusEvent> = flow

    fun lose(e: FocusEvent = FocusEvent.LOST_TRANSIENT) {
        flow.tryEmit(e)
    }
}

class FakePlatform(var rate: Int = 48_000) : TestPlatform {
    override fun outputSampleRate() = rate
    override fun appVersion() = "1.2.3-test"
    override fun defaultListenerName() = "Me"
    override fun defaultProfileName(headsetName: String) = headsetName.ifBlank { "My headphones" }
}

class FakeStore : TestStore {
    val saved = CopyOnWriteArrayList<SaveRequest>()
    @Volatile var check: TargetCheck = TargetCheck.Ok
    @Volatile var failSave = false

    override suspend fun check(device: OutputDevice?, requestedProfileId: Long?): TargetCheck =
        if (device == null || !device.isProcessable) TargetCheck.NotProcessable else check

    override suspend fun save(request: SaveRequest): Long {
        if (failSave) error("disk full")
        saved += request
        return 41L + saved.size
    }
}

/** Virtual-clock [Clock]: monotonic time is whatever [now] says (the test scheduler's time, or real time). */
class FakeClock(private val now: () -> Long) : Clock {
    override fun wallMs(): Long = 1_700_000_000_000L + now()
    override fun monotonicMs(): Long = now()
}

/** Peak level of a buffer in dBFS, the ear it is in, and whether it is silent (a catch trial). */
class BufferInfo(val ear: Ear?, val levelDbfs: Double, val silent: Boolean)

fun StereoBuffer.info(): BufferInfo {
    val l = left.maxOfOrNull { abs(it) } ?: 0f
    val r = right.maxOfOrNull { abs(it) } ?: 0f
    val peak = maxOf(l, r)
    if (peak == 0f) return BufferInfo(null, Double.NEGATIVE_INFINITY, true)
    val ear = when {
        l > 0f && r == 0f -> Ear.LEFT
        r > 0f && l == 0f -> Ear.RIGHT
        else -> null // both channels (calibration)
    }
    return BufferInfo(ear, 20.0 * log10(peak.toDouble()), false)
}

/** True thresholds around `L_exp` for a calibrated `lRef`, one value per (ear, frequency). */
fun truthAround(lRef: Double, seed: Long, rel: (Int) -> Double = { 0.0 }): Map<PointKey, Double> {
    val rng = Random(seed)
    val m = HashMap<PointKey, Double>()
    for (f in FrequencyGrid.TEST_FREQUENCIES_HZ) {
        val b = Iso389_7.bounds(f, lRef)
        for (ear in Ear.entries) {
            m[PointKey(ear, f)] = (b.lExp + rel(f) + rng.nextDouble() * 4 - 2).coerceIn(b.lMin + 5, b.lMax - 8)
        }
    }
    return m
}

fun quickTrialOf(info: BufferInfo, freqHz: Int): Trial =
    Trial(0, info.ear ?: Ear.LEFT, freqHz, if (info.silent) null else info.levelDbfs, isCatch = info.silent)

/** Convenience for tests that need a processable output other than the default. */
val OTHER_BUDS = OutputDevice(my.umarh.paudio.core.model.OutputDeviceType.BLUETOOTH_A2DP, "Other Buds", "12:34")
