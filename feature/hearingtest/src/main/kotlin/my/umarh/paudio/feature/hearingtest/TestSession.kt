package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import my.umarh.paudio.audio.tone.FocusEvent
import my.umarh.paudio.audio.tone.VolumeLock
import my.umarh.paudio.core.audiometry.Calibration
import my.umarh.paudio.core.audiometry.CalibrationAbortReason
import my.umarh.paudio.core.audiometry.CalibrationOutcome
import my.umarh.paudio.core.audiometry.CalibrationSession
import my.umarh.paudio.core.audiometry.DialRun
import my.umarh.paudio.core.audiometry.PointKey
import my.umarh.paudio.core.audiometry.PointResult
import my.umarh.paudio.core.audiometry.QuickTestEngine
import my.umarh.paudio.core.audiometry.StimulusSynth
import my.umarh.paudio.core.audiometry.ThoroughTestEngine
import my.umarh.paudio.core.audiometry.Trial
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.InterruptibleToneOutput
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDevices
import my.umarh.paudio.core.model.VolumeController
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.StereoBuffer
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.core.model.TrialJson
import my.umarh.paudio.data.db.HearingTestEntity
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.db.TrialEntity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.random.Random

/** Everything a [TestSession] talks to; each is an interface or a pure class, so the whole flow runs on the JVM. */
class SessionDeps @Inject constructor(
    val tone: InterruptibleToneOutput,
    val volume: VolumeController,
    val bypass: BypassController,
    val devices: OutputDevices,
    val focus: FocusSource,
    val platform: TestPlatform,
    val clock: Clock,
    val store: TestStore,
    val calibrations: CalibrationCache,
)

class SessionConfig(
    val mode: TestMode,
    val ancOn: Boolean,
    val requestedProfileId: Long?,
    val random: Random = Random.Default,
    /** Longest continuous pause before the session gives the engine back (see DECISIONS §20). */
    val pauseTimeoutMs: Long = DEFAULT_PAUSE_TIMEOUT_MS,
    /** Catch trials needed before the mid-test "answers looked like guesses" prompt can fire. */
    val earlyReliabilityMinCatches: Int = TestSession.EARLY_RELIABILITY_MIN_CATCHES,
) {
    companion object {
        const val DEFAULT_PAUSE_TIMEOUT_MS = 5L * 60L * 1000L
    }
}

private class AbortException(val reason: AbortReason) : RuntimeException(reason.name)

/** Thrown inside a unit of work that must be redone after the pause that is now active. */
private class Interrupted : CancellationException("test paused")

private class Box<T>(val value: T)

private sealed interface DialAction {
    data class Confirm(val position: Int) : DialAction

    data class Limit(val position: Int) : DialAction
}

private class CalibrationOutcomeData(
    val index: Int,
    val maxIndex: Int,
    val lRefDbfs: Double,
    val warning: Boolean,
)

/**
 * One hearing-test session: `Starting → ChannelCheck → Calibrating → Testing ⇄ Paused → Saving → Finished | Aborted`
 * (SPEC §4 item 8). Pure Kotlin over interfaces.
 *
 * Threading contract (SPEC §5.3): everything runs as ONE coroutine on [scope]'s dispatcher, which the owner makes a
 * dedicated single-thread dispatcher. The engines ([QuickTestEngine], [ThoroughTestEngine]) are only ever touched
 * from that coroutine. Public input functions are thread-safe (atomics / StateFlow / `scope.launch`).
 *
 * P0 (SPEC §2.2 #1): ONE [BypassToken] is acquired before the stream is opened and closed LAST in `finally`; the
 * engine is never un-bypassed in between, also while paused.
 *
 * Cancellation (SPEC §5.3): cancelling the job at any suspension point (or an exception) runs one `finally` that
 * closes the tone output, restores the volume, abandons focus and then releases the bypass token, each step
 * guarded so a failure in one cannot skip the next. The terminal [SessionState] is published AFTER that cleanup.
 */
class TestSession(
    private val config: SessionConfig,
    private val deps: SessionDeps,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state

    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<SessionSignal> = _signals

    /** True while a stimulus is audible (drives the pulse / wave amplitude; haptics are suppressed while true). */
    val stimulusPlaying: StateFlow<Boolean> get() = deps.tone.playing

    private val rng = config.random
    private var job: Job? = null

    // ---- pause machinery -------------------------------------------------------------------------------------
    private val reasons = MutableStateFlow<Set<PauseReason>>(emptySet())
    private val headsetOk = MutableStateFlow(true)
    private val foreground = MutableStateFlow(true)
    private val volumeKeysNeeded = MutableStateFlow(false)
    private val driftSuppressed = AtomicBoolean(false)
    private var testFinishedPrompt = false

    // ---- user input ------------------------------------------------------------------------------------------
    private val channelRef = AtomicReference<CompletableDeferred<ChannelAnswer>?>(null)
    private val tapRef = AtomicReference<CompletableDeferred<Double>?>(null)
    private val manualVolumeRef = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val responseRef = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val dialRef = AtomicReference<CompletableDeferred<DialAction>?>(null)
    private val redoRequested = AtomicBoolean(false)
    private val unreliableAcknowledged = AtomicBoolean(false)

    @Volatile private var currentRampLevel = Calibration.START_DBFS

    @Volatile private var currentRun: DialRun? = null
    private val dialPosition = MutableStateFlow(0)

    // ---- session facts ---------------------------------------------------------------------------------------
    private lateinit var startDevice: OutputDevice
    private lateinit var lock: VolumeLock
    private var lockTarget = 0
    private var sampleRate = 48_000
    private var t0 = 0L
    private var calWarning = false

    /** The unexpected exception that ended the session (diagnostics/tests only; never logged). */
    @Volatile internal var lastFailure: Throwable? = null

    val isActive: Boolean get() = job?.isActive == true

    fun start(): Job {
        check(job == null) { "already started" }
        return scope.launch { run() }.also { job = it }
    }

    /** Cancels the session and waits for its cleanup; safe to call repeatedly. */
    suspend fun cancelAndJoin() {
        job?.cancel()
        job?.join()
    }

    fun cancel() {
        job?.cancel()
    }

    // ---- public inputs ---------------------------------------------------------------------------------------

    fun answerChannel(answer: ChannelAnswer) {
        channelRef.get()?.complete(answer)
    }

    /** "I hear it" during the calibration ramp; captures the level playing at this moment. */
    fun calibrationTap() {
        tapRef.get()?.complete(currentRampLevel)
    }

    /** After the user changed the volume with the keys (refused `setStreamVolume`). */
    fun manualVolumeDone() {
        manualVolumeRef.get()?.complete(Unit)
    }

    /** Quick: "I heard it" / "I didn't". Counts only while a trial is open (stimulus presented, unanswered). */
    fun respond(heard: Boolean) {
        responseRef.get()?.complete(heard)
    }

    /** Thorough: dial moved to [position] (1 dB per detent; the mapping to a level is secret to the UI). */
    fun dialTo(position: Int) {
        val run = currentRun ?: return
        val clamped = position.coerceIn(run.minPosition, run.maxPosition)
        dialPosition.value = clamped
        _state.update { s ->
            if (s is SessionState.Testing && s.dial != null) s.copy(dial = dialUi(run, clamped)) else s
        }
    }

    /** Thorough: "I can't hear it anymore" (descending) / "I can hear it now" (ascending). */
    fun dialConfirm() {
        dialRef.get()?.complete(DialAction.Confirm(dialPosition.value))
    }

    /** Thorough: confirm at a dial limit ("not even at the top" / "still at the bottom"). Ignored elsewhere. */
    fun dialLimit() {
        val run = currentRun ?: return
        val pos = dialPosition.value
        val level = run.levelAt(pos)
        if (level >= run.lMax - 1e-9 || level <= run.lMin + 1e-9) dialRef.get()?.complete(DialAction.Limit(pos))
    }

    fun onBackgrounded() {
        foreground.value = false
        if (isTestRunning()) raise(PauseReason.BACKGROUNDED)
    }

    fun onForegrounded() {
        foreground.value = true
    }

    /** The user asked to continue. Re-establishes focus / volume first; stays paused when that fails. */
    fun resume() {
        scope.launch { resumeNow() }
    }

    /** UNRELIABLE prompt: redo the whole Quick test. */
    fun redoTest() {
        redoRequested.set(true)
        reasons.update { it - PauseReason.UNRELIABLE }
    }

    /** UNRELIABLE prompt: keep going / keep the results (saved flagged unreliable). */
    fun keepGoing() {
        unreliableAcknowledged.set(true)
        reasons.update { it - PauseReason.UNRELIABLE }
    }

    // ---- main flow -------------------------------------------------------------------------------------------

    private fun isTestRunning(): Boolean = _state.value.let {
        it !is SessionState.Finished && it !is SessionState.Aborted && it !is SessionState.Saving
    }

    private suspend fun run() {
        var token: BypassToken? = null
        var focusHeld = false
        var terminal: SessionState? = null
        val startedAtWall = deps.clock.wallMs()
        t0 = deps.clock.monotonicMs()
        lock = VolumeLock(deps.volume)
        try {
            val device = deps.devices.current.value
            if (device == null || !device.isProcessable) throw AbortException(AbortReason.NO_HEADPHONES)
            startDevice = device
            sampleRate = deps.platform.outputSampleRate()
            _state.value = SessionState.Starting

            // P0: bypass BEFORE any sound; closed last in the finally block.
            token = deps.bypass.acquire(BypassReason.HEARING_TEST)
            try {
                deps.tone.open(sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AbortException(AbortReason.AUDIO_ERROR)
            }
            focusHeld = true
            val focusGranted = deps.focus.request()

            // Hearing safety: volume goes to round(0.5 x max) first; the original is restored in finally.
            val wanted = Calibration.initialIndex(deps.volume.maxIndex)
            val effective = lock.lock(wanted)
            lockTarget = effective

            coroutineScope {
                val monitors = launchMonitors()
                try {
                    if (!focusGranted) raise(PauseReason.FOCUS_LOST)
                    channelCheck()
                    val cal = calibrate(effective, wanted)
                    calWarning = cal.warning
                    val saveData = when (config.mode) {
                        TestMode.QUICK -> runQuick(cal)
                        TestMode.THOROUGH -> runThorough(cal)
                    }
                    _state.value = SessionState.Saving
                    val id = try {
                        deps.store.save(
                            SaveRequest(
                                requestedProfileId = config.requestedProfileId,
                                device = startDevice,
                                test = HearingTestEntity(
                                    profileId = 0,
                                    mode = config.mode,
                                    startedAt = startedAtWall,
                                    durationMs = deps.clock.monotonicMs() - t0,
                                    volIndex = cal.index,
                                    volMax = cal.maxIndex,
                                    lRef = cal.lRefDbfs.toFloat(),
                                    deviceSnapshotJson = buildJsonObject {
                                        put("type", startDevice.type.name)
                                        put("productName", startDevice.productName)
                                        put("addressSuffix", startDevice.addressSuffix)
                                        put("calibrationWarning", cal.warning)
                                    }.toString(),
                                    ancOn = config.ancOn,
                                    appVersion = deps.platform.appVersion(),
                                    algoVersion = ALGO_VERSION,
                                    fpRate = saveData.fpRate,
                                    reliable = saveData.reliable,
                                ),
                                thresholds = saveData.thresholds,
                                trials = saveData.trials,
                            ),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw AbortException(AbortReason.SAVE_FAILED)
                    }
                    terminal = SessionState.Finished(id)
                } finally {
                    monitors.cancel()
                }
            }
        } catch (e: AbortException) {
            terminal = SessionState.Aborted(e.reason)
        } catch (e: CancellationException) {
            terminal = SessionState.Aborted(AbortReason.USER)
            throw e
        } catch (e: Throwable) {
            lastFailure = e
            terminal = SessionState.Aborted(AbortReason.AUDIO_ERROR)
        } finally {
            // Order matters (P0): nothing may be audible once the token is gone, so the stream goes first and the
            // bypass token last. Every step is guarded: a failure in one must not skip the next.
            runCatching { deps.tone.close() }
            runCatching { if (::lock.isInitialized) lock.release() }
            runCatching { if (focusHeld) deps.focus.abandon() }
            runCatching { token?.close() }
            _state.value = terminal ?: SessionState.Aborted(AbortReason.USER)
        }
    }

    private fun CoroutineScope.launchMonitors(): Job = launch {
        val jobs = listOf(
            launch {
                deps.devices.current.collect { d ->
                    val ok = d != null && d.isProcessable && d.headsetKey == startDevice.headsetKey
                    headsetOk.value = ok
                    if (!ok) raise(PauseReason.HEADSET_DISCONNECTED)
                }
            },
            launch {
                // Re-check the live index: a stale event (already corrected by a resume) must not pause again.
                lock.drift.collect {
                    val locked = lock.lockedIndex
                    if (!driftSuppressed.get() && locked != null && deps.volume.index() != locked) {
                        raise(PauseReason.VOLUME_CHANGED)
                    }
                }
            },
            launch {
                deps.focus.events.collect { e ->
                    if (e == FocusEvent.LOST || e == FocusEvent.LOST_TRANSIENT || e == FocusEvent.LOST_CAN_DUCK) {
                        raise(PauseReason.FOCUS_LOST)
                    }
                }
            },
        )
        joinAll(*jobs.toTypedArray())
    }

    // ---- pause handling --------------------------------------------------------------------------------------

    private fun raise(reason: PauseReason) {
        reasons.update { it + reason }
    }

    private fun resumeNow() {
        val current = reasons.value
        if (current.isEmpty() || !foreground.value) return
        val cleared = mutableSetOf<PauseReason>()
        if (PauseReason.HEADSET_DISCONNECTED in current) {
            if (!headsetOk.value) return
            cleared += PauseReason.HEADSET_DISCONNECTED
        }
        if (PauseReason.FOCUS_LOST in current && deps.focus.reacquire()) cleared += PauseReason.FOCUS_LOST
        if (PauseReason.VOLUME_CHANGED in current) {
            val effective = lock.lock(lockTarget)
            if (effective == lockTarget) {
                cleared += PauseReason.VOLUME_CHANGED
                volumeKeysNeeded.value = false
            } else {
                volumeKeysNeeded.value = true
            }
        }
        if (PauseReason.BACKGROUNDED in current) cleared += PauseReason.BACKGROUNDED
        reasons.update { it - cleared }
    }

    private suspend fun awaitNotPaused() {
        if (reasons.value.isEmpty()) return
        val finished = withTimeoutOrNull(config.pauseTimeoutMs) {
            combine(reasons, headsetOk, foreground, volumeKeysNeeded) { r, h, f, v -> PausedView(r, h && f, v) }
                .onEach { publishPaused(it) }
                .first { it.reasons.isEmpty() }
            true
        }
        if (finished == null) throw AbortException(AbortReason.PAUSE_TIMEOUT)
    }

    private class PausedView(val reasons: Set<PauseReason>, val canResume: Boolean, val volumeKeysNeeded: Boolean)

    private fun publishPaused(v: PausedView) {
        val top = PauseReason.entries.firstOrNull { it in v.reasons } ?: return
        _state.value = SessionState.Paused(
            reason = top,
            all = v.reasons,
            canResume = v.canResume,
            volumeKeysNeeded = v.volumeKeysNeeded,
            calibrating = inCalibration,
            testFinished = testFinishedPrompt,
        )
    }

    @Volatile private var inCalibration = false
    private var streamFailures = 0

    /**
     * Runs [block] as one interruptible unit of work: it is cancelled the moment any pause reason is raised (the
     * tone is ramped down by the cancellation), and re-run after the user resumed. [onInterrupted] restores the
     * engine state of the interrupted unit (restart only that point/run). A lost stream (`IllegalStateException`
     * from the tone output) re-opens the stream and redoes the unit, at most [MAX_STREAM_FAILURES] times.
     */
    private suspend fun <T> pausable(onInterrupted: () -> Unit = {}, block: suspend () -> T): T {
        while (true) {
            awaitNotPaused()
            val box = try {
                runUntilPaused(block)
            } catch (e: CancellationException) {
                throw e // java.util.concurrent.CancellationException IS an IllegalStateException: never swallow it
            } catch (e: IllegalStateException) {
                lastFailure = e
                if (++streamFailures > MAX_STREAM_FAILURES) throw AbortException(AbortReason.AUDIO_ERROR)
                try {
                    deps.tone.open(sampleRate)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    throw AbortException(AbortReason.AUDIO_ERROR)
                }
                null
            }
            if (box != null) return box.value
            onInterrupted()
        }
    }

    private suspend fun <T> runUntilPaused(block: suspend () -> T): Box<T>? = try {
        coroutineScope {
            val work = async { block() }
            val watcher = launch {
                reasons.first { it.isNotEmpty() }
                work.cancel(Interrupted())
            }
            try {
                Box(work.await())
            } finally {
                watcher.cancel()
            }
        }
    } catch (e: Interrupted) {
        null
    }

    private fun ensureVolumeUnchanged() {
        val locked = lock.lockedIndex
        if (!driftSuppressed.get() && locked != null && deps.volume.index() != locked) {
            raise(PauseReason.VOLUME_CHANGED)
            throw Interrupted()
        }
    }

    // ---- channel check (F3) ----------------------------------------------------------------------------------

    private suspend fun channelCheck() {
        var problem = ChannelCheckProblem.NONE
        var level = CHANNEL_CHECK_START_DBFS
        while (true) {
            val order = if (rng.nextBoolean()) listOf(Ear.LEFT, Ear.RIGHT) else listOf(Ear.RIGHT, Ear.LEFT)
            var allCorrect = true
            for ((i, ear) in order.withIndex()) {
                while (true) {
                    _state.value = SessionState.ChannelCheck(i + 1, order.size, problem)
                    val answer = pausable { playUntilChannelAnswer(ear, level) }
                    if (answer == ChannelAnswer.LOUDER) {
                        if (level >= CHANNEL_CHECK_CAP_DBFS) throw AbortException(AbortReason.CANT_HEAR)
                        level = minOf(CHANNEL_CHECK_CAP_DBFS, level + CHANNEL_CHECK_STEP_DB)
                        problem = ChannelCheckProblem.TOO_QUIET
                        continue
                    }
                    problem = ChannelCheckProblem.NONE
                    if (answer.ear != ear) {
                        allCorrect = false
                        problem = ChannelCheckProblem.WRONG_EAR
                    }
                    break
                }
                if (!allCorrect) break
            }
            if (allCorrect) return
            _state.value = SessionState.ChannelCheck(1, order.size, ChannelCheckProblem.WRONG_EAR)
            pausable { deps.tone.playSilence(CHECK_RETRY_PAUSE_MS) } // let the message be read before the retry
        }
    }

    private val ChannelAnswer.ear: Ear?
        get() = when (this) {
            ChannelAnswer.LEFT -> Ear.LEFT
            ChannelAnswer.RIGHT -> Ear.RIGHT
            else -> null
        }

    private suspend fun playUntilChannelAnswer(ear: Ear, level: Double): ChannelAnswer = coroutineScope {
        val answer = CompletableDeferred<ChannelAnswer>().also { channelRef.set(it) }
        val player = launch {
            while (true) {
                deps.tone.play(StimulusSynth.threePulse(ear, CHECK_FREQ_HZ, level, sampleRate))
                deps.tone.playSilence(CHECK_GAP_MS)
            }
        }
        try {
            answer.await()
        } finally {
            channelRef.set(null)
            player.cancel()
        }
    }

    // ---- calibration (F4) ------------------------------------------------------------------------------------

    private suspend fun calibrate(effectiveStart: Int, requestedStart: Int): CalibrationOutcomeData {
        val key = startDevice.headsetKey.value
        val maxIndex = deps.volume.maxIndex
        deps.calibrations.get(key, maxIndex, deps.clock.monotonicMs())?.let { cached ->
            val eff = lock.lock(cached.index)
            if (eff == cached.index) {
                lockTarget = eff
                return CalibrationOutcomeData(cached.index, cached.maxIndex, cached.lRefDbfs, cached.warning)
            }
            lock.lock(effectiveStart) // reuse impossible: fall back to the measured start index
        }
        val session = CalibrationSession(0, maxIndex, deps.volume::indexDb, startIndex = effectiveStart)
        var rerun = 0
        var refused = effectiveStart != requestedStart
        inCalibration = true
        try {
            while (true) {
                _state.value = SessionState.Calibrating(CalibrationPhase.PREPARING, rerun, canTap = false, volumeRefused = refused)
                val tap = pausable { runRamp(session, rerun, refused) }
                val outcome = if (tap == null) session.onRampExhausted() else session.onTap(tap)
                when (outcome) {
                    is CalibrationOutcome.Accepted -> {
                        val r = outcome.result
                        lockTarget = r.index
                        deps.calibrations.put(
                            CachedCalibration(key, r.index, r.maxIndex, r.lRefDbfs, r.warning, deps.clock.monotonicMs()),
                        )
                        return CalibrationOutcomeData(r.index, r.maxIndex, r.lRefDbfs, r.warning)
                    }
                    is CalibrationOutcome.Aborted -> throw AbortException(
                        when (outcome.reason) {
                            CalibrationAbortReason.CANT_HEAR -> AbortReason.CANT_HEAR
                            CalibrationAbortReason.VOLUME_RANGE_UNSUITABLE -> AbortReason.VOLUME_RANGE
                        },
                    )
                    is CalibrationOutcome.Rerun -> {
                        val want = outcome.newIndex
                        val from = session.currentIndex
                        lockTarget = want
                        var effective = lock.lock(want)
                        if (effective != want) {
                            // setStreamVolume was refused (safe-media-volume, fixed volume): ask for the volume keys.
                            val direction = if (want > from) VolumeDirection.UP else VolumeDirection.DOWN
                            effective = manualVolume(rerun + 1, direction)
                            lockTarget = effective
                        }
                        session.restart(effective)
                        rerun++
                        refused = false
                    }
                }
            }
        } finally {
            inCalibration = false
        }
    }

    /** Waits until the user has set the volume with the keys; adopts the volume as the new locked index. */
    private suspend fun manualVolume(rerun: Int, direction: VolumeDirection): Int {
        driftSuppressed.set(true)
        try {
            pausable {
                _state.value = SessionState.Calibrating(CalibrationPhase.MANUAL_VOLUME, rerun, canTap = false, direction = direction)
                val done = CompletableDeferred<Unit>().also { manualVolumeRef.set(it) }
                try {
                    done.await()
                } finally {
                    manualVolumeRef.set(null)
                }
            }
            return lock.lock(deps.volume.index())
        } finally {
            driftSuppressed.set(false)
        }
    }

    /** Plays the ascending ramp; returns the level at the tap or null when the ramp ran out (-10 dBFS cap). */
    private suspend fun runRamp(session: CalibrationSession, rerun: Int, volumeRefused: Boolean): Double? = coroutineScope<Double?> {
        val tap = CompletableDeferred<Double>().also { tapRef.set(it) }
        try {
            var step = 0
            while (true) {
                val level = session.rampLevelAtMs(step.toLong() * Calibration.STEP_MS) ?: return@coroutineScope null
                currentRampLevel = level
                if (step == 0) {
                    _state.value = SessionState.Calibrating(CalibrationPhase.RAMP, rerun, canTap = true, volumeRefused = volumeRefused)
                }
                val cycle = StimulusSynth.calibrationCycle(level, sampleRate)
                val player = launch { deps.tone.play(cycle) }
                val tapped = select<Double?> {
                    tap.onAwait { it }
                    player.onJoin { null }
                }
                if (tapped != null) {
                    player.cancel()
                    return@coroutineScope tapped
                }
                step++
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            tapRef.set(null)
        }
    }

    // ---- Quick (F5) ------------------------------------------------------------------------------------------

    private class SaveData(
        val thresholds: List<ThresholdEntity>,
        val trials: List<TrialEntity>,
        val fpRate: Float?,
        val reliable: Boolean,
    )

    private class QuickRun(val engine: QuickTestEngine) {
        var inFlight: Trial? = null
        val times = HashMap<Int, Long>()
    }

    private suspend fun runQuick(cal: CalibrationOutcomeData): SaveData {
        while (true) {
            redoRequested.set(false)
            unreliableAcknowledged.set(false)
            val run = QuickRun(QuickTestEngine(cal.lRefDbfs, Random(rng.nextLong())))
            quickLoop(run)
            if (redoRequested.get()) continue
            val engine = run.engine
            if (engine.isUnreliable && !unreliableAcknowledged.get()) {
                // End-of-test prompt: redo, or keep the results flagged unreliable.
                testFinishedPrompt = true
                raise(PauseReason.UNRELIABLE)
                try {
                    awaitNotPaused()
                } finally {
                    testFinishedPrompt = false
                }
                if (redoRequested.get()) continue
            }
            return SaveData(
                thresholds = thresholdsOf(engine.results()),
                trials = engine.persistableTrials { seq -> run.times[seq] ?: 0L }.map { it.toEntity() },
                fpRate = engine.falsePositiveRate.toFloat(),
                reliable = !engine.isUnreliable,
            )
        }
    }

    private suspend fun quickLoop(run: QuickRun) {
        while (!redoRequested.get()) {
            val done = pausable(
                onInterrupted = {
                    // Resume restarts ONLY the interrupted point (F8): a pending catch trial is simply dropped.
                    run.inFlight?.let { t ->
                        run.engine.cancelPending()
                        if (!t.isCatch) run.engine.restartPoint(t.ear, t.freqHz)
                    }
                    run.inFlight = null
                },
            ) { quickStep(run) }
            if (done) return
        }
    }

    /** One trial: silence gap, stimulus, answer. Returns true when the engine has no trial left. */
    private suspend fun quickStep(run: QuickRun): Boolean {
        if (redoRequested.get()) return true
        val engine = run.engine
        deps.tone.playSilence(rng.nextInt(QUICK_GAP_MIN_MS, QUICK_GAP_MAX_MS + 1))
        ensureVolumeUnchanged()
        val trial = engine.next() ?: return true
        run.inFlight = trial
        run.times[trial.seq] = deps.clock.monotonicMs() - t0
        _state.value = quickUi(engine, trial.freqHz, trialOpen = true)

        val heard = presentQuickTrial(trial)

        engine.submit(heard)
        run.inFlight = null
        // Haptics / announcements only now: the stimulus has been stopped (trap #20).
        _signals.tryEmit(SessionSignal.Responded(heard))
        _state.value = quickUi(engine, trial.freqHz, trialOpen = false)
        if (trial.isCatch && !unreliableAcknowledged.get() &&
            engine.catchTrialCount >= config.earlyReliabilityMinCatches && engine.isUnreliable
        ) {
            raise(PauseReason.UNRELIABLE)
        }
        return false
    }

    private fun quickUi(engine: QuickTestEngine, freq: Int, trialOpen: Boolean) = SessionState.Testing(
        mode = TestMode.QUICK,
        progress = engine.progress.toFloat(),
        completedPoints = engine.completedPoints,
        totalPoints = engine.totalPoints,
        frequencyHz = freq,
        trialOpen = trialOpen,
        dial = null,
        calibrationWarning = calWarning,
    )

    /**
     * Presents the stimulus and waits for the answer. A response DURING the stimulus counts and ends it early
     * (25 ms ramp-down via `stopCurrent`, SPEC §4); the stimulus is silent before the caller signals the response.
     */
    private suspend fun presentQuickTrial(trial: Trial): Boolean = coroutineScope {
        val answer = CompletableDeferred<Boolean>()
        val buffer = if (trial.isCatch) silentStimulus() else StimulusSynth.threePulse(trial.ear, trial.freqHz, trial.levelDbfs!!, sampleRate)
        val player = launch { deps.tone.play(buffer) }
        responseRef.set(answer)
        try {
            val heard = answer.await()
            if (deps.tone.playing.value) deps.tone.stopCurrent()
            if (player.isActive) player.cancel() // queued but not audible yet, or still draining
            player.join()
            heard
        } finally {
            responseRef.set(null)
            player.cancel()
        }
    }

    private fun silentStimulus(): StereoBuffer {
        val n = StimulusSynth.msToSamples(StimulusSynth.STIMULUS_MS, sampleRate)
        return StereoBuffer(FloatArray(n), FloatArray(n), sampleRate)
    }

    // ---- Thorough (F6) ---------------------------------------------------------------------------------------

    private suspend fun runThorough(cal: CalibrationOutcomeData): SaveData {
        val engine = ThoroughTestEngine(cal.lRefDbfs, Random(rng.nextLong()))
        val runTimes = mutableListOf<Long>()
        while (true) {
            val done = pausable(onInterrupted = { engine.cancelRun(); currentRun = null }) { thoroughStep(engine, runTimes) }
            if (done) break
        }
        return SaveData(
            thresholds = thresholdsOf(engine.results()),
            trials = engine.persistableTrials { i -> runTimes.getOrElse(i - 1) { 0L } }.map { it.toEntity() },
            fpRate = null,
            reliable = true, // per-point low-confidence flags carry the Thorough reliability (SPEC §2.1.3)
        )
    }

    private suspend fun thoroughStep(engine: ThoroughTestEngine, runTimes: MutableList<Long>): Boolean {
        deps.tone.playSilence(THOROUGH_PRE_RUN_MS)
        ensureVolumeUnchanged()
        val run = engine.nextRun() ?: return true
        currentRun = run
        dialPosition.value = run.initialPosition
        _state.value = thoroughUi(engine, run)
        val action = presentDialRun(run)
        currentRun = null
        when (action) {
            is DialAction.Confirm -> engine.confirm(action.position)
            is DialAction.Limit -> engine.reportLimit(action.position)
        }
        runTimes += deps.clock.monotonicMs() - t0
        _signals.tryEmit(SessionSignal.RunConfirmed)
        _state.value = SessionState.Testing(
            TestMode.THOROUGH, engine.progress.toFloat(), engine.completedPoints, engine.totalPoints,
            run.freqHz, trialOpen = false, dial = null, calibrationWarning = calWarning,
        )
        return false
    }

    private fun dialUi(run: DialRun, position: Int) = DialUi(
        minPosition = run.minPosition,
        maxPosition = run.maxPosition,
        position = position,
        kind = run.kind,
        atTop = run.levelAt(position) >= run.lMax - 1e-9,
        atBottom = run.levelAt(position) <= run.lMin + 1e-9,
    )

    private fun thoroughUi(engine: ThoroughTestEngine, run: DialRun) = SessionState.Testing(
        mode = TestMode.THOROUGH,
        progress = engine.progress.toFloat(),
        completedPoints = engine.completedPoints,
        totalPoints = engine.totalPoints,
        frequencyHz = run.freqHz,
        trialOpen = true,
        dial = dialUi(run, run.initialPosition),
        calibrationWarning = calWarning,
    )

    /**
     * Loops the pulsed tone at the dial's current level until the user confirms. A level change takes effect at the
     * next 425 ms loop period (each period is one pulse + gap; digital silence flows between enqueues).
     */
    private suspend fun presentDialRun(run: DialRun): DialAction = coroutineScope {
        val action = CompletableDeferred<DialAction>().also { dialRef.set(it) }
        val looper = launch {
            while (true) {
                val level = run.levelAt(dialPosition.value)
                deps.tone.play(StimulusSynth.loopingPulsedTone(run.ear, run.freqHz, level, sampleRate))
            }
        }
        try {
            val a = action.await()
            looper.cancel() // ramps the current period down; the stream continues in silence
            looper.join()
            a
        } finally {
            dialRef.set(null)
            looper.cancel()
        }
    }

    // ---- mapping ---------------------------------------------------------------------------------------------

    private fun thresholdsOf(results: Map<PointKey, PointResult>): List<ThresholdEntity> =
        results.values.sortedWith(compareBy({ it.freqHz }, { it.ear })).map {
            ThresholdEntity(
                testId = 0, ear = it.ear, freqHz = it.freqHz, levelDbfs = it.levelDbfs.toFloat(),
                censored = it.censored, lowConfidence = it.lowConfidence, trials = it.trials,
            )
        }

    private fun TrialJson.toEntity() = TrialEntity(
        testId = 0, seq = seq, ear = ear, freqHz = freqHz, levelDbfs = levelDbfs, isCatch = isCatch,
        response = response, tMs = tMs, run = run,
    )

    companion object {
        /** Persisted as `HearingTest.algoVersion`; bump when the measurement/calibration algorithm changes. */
        const val ALGO_VERSION = 1

        // Channel check (F3). The level is chosen BEFORE calibration, so it is conservative: 20 dB below the
        // calibration cap and raised in 5 dB steps only when the user says they can't hear it.
        const val CHECK_FREQ_HZ = 1000
        const val CHANNEL_CHECK_START_DBFS = -45.0
        const val CHANNEL_CHECK_STEP_DB = 5.0
        const val CHANNEL_CHECK_CAP_DBFS = -30.0
        const val CHECK_GAP_MS = 1500
        const val CHECK_RETRY_PAUSE_MS = 1200

        const val QUICK_GAP_MIN_MS = 400
        const val QUICK_GAP_MAX_MS = 1200
        const val THOROUGH_PRE_RUN_MS = 800

        /** The mid-test "guessing" prompt waits for this many catch trials so one slip can't trigger it. */
        const val EARLY_RELIABILITY_MIN_CATCHES = 8
        const val MAX_STREAM_FAILURES = 3
    }
}
