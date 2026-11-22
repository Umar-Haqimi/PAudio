package my.umarh.paudio.feature.hearingtest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import my.umarh.paudio.core.audiometry.PointKey
import my.umarh.paudio.core.audiometry.QuickResponder
import my.umarh.paudio.core.audiometry.SimulatedListener
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.StereoBuffer
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.testing.FakeOutputDevices
import my.umarh.paudio.testing.FakeVolumeController
import kotlin.math.roundToInt
import kotlin.random.Random

/** Everything wired together over the doubles; the session runs on [sessionDispatcher]. */
class Rig(
    val mode: TestMode,
    val clockMs: () -> Long,
    val sessionDispatcher: CoroutineDispatcher,
    timed: Boolean,
    seed: Long = 1,
    pauseTimeoutMs: Long = SessionConfig.DEFAULT_PAUSE_TIMEOUT_MS,
    initialVolume: Int = 11,
    earlyMinCatches: Int = TestSession.EARLY_RELIABILITY_MIN_CATCHES,
    val cache: CalibrationCache = CalibrationCache(),
    bypassOverride: BypassController? = null,
    toneFactory: ((() -> Boolean, EventLog) -> HookedTone)? = null,
    val gate: SessionGate = SessionGate(),
    requestedProfileId: Long? = null,
) {
    val log = EventLog()
    val trace = TraceBypass(log)
    val bypass: BypassController = bypassOverride ?: trace
    val tone: HookedTone = (toneFactory ?: { held, l -> ScriptedTone(held, l, timed) })({ bypass.bypassed.value }, log)
    val volume = FakeVolumeController(maxIndex = 15, initialIndex = initialVolume)
    val devices = FakeOutputDevices(FakeOutputDevices.WIRED_HEADPHONES)
    val focus = FakeFocus()
    val store = FakeStore()
    val platform = FakePlatform()
    val deps = SessionDeps(tone, volume, bypass, devices, focus, platform, FakeClock(clockMs), store, cache, gate)
    val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    val session = TestSession(
        SessionConfig(
            mode, ancOn = true, requestedProfileId = requestedProfileId, random = Random(seed), pauseTimeoutMs = pauseTimeoutMs,
            earlyReliabilityMinCatches = earlyMinCatches,
        ),
        deps,
        scope,
    )
    val originalVolume = initialVolume

    companion object {
        fun virtual(
            scheduler: TestCoroutineScheduler,
            mode: TestMode,
            timed: Boolean,
            seed: Long = 1,
            pauseTimeoutMs: Long = SessionConfig.DEFAULT_PAUSE_TIMEOUT_MS,
            earlyMinCatches: Int = TestSession.EARLY_RELIABILITY_MIN_CATCHES,
            cache: CalibrationCache = CalibrationCache(),
            gate: SessionGate = SessionGate(),
            requestedProfileId: Long? = null,
        ) = Rig(
            mode, { scheduler.currentTime }, StandardTestDispatcher(scheduler), timed, seed, pauseTimeoutMs,
            earlyMinCatches = earlyMinCatches, cache = cache, gate = gate, requestedProfileId = requestedProfileId,
        )
    }
}

/**
 * The simulated person. Answers from inside the tone's `onPlay` hook (the stimulus that was just started), or after a
 * virtual-time reaction delay when [scope]/[heardMs] are given. Reads the stimulus from the buffer (ear, level,
 * silence = catch), the frequency from the session state.
 */
class Brain(
    private val rig: Rig,
    private val listener: SimulatedListener,
    private val responder: QuickResponder = listener,
    private val scope: CoroutineScope? = null,
    /** Reaction delays; 0 answers synchronously. SPEC §9: 1.0 s per answer, 12 s per Thorough run. */
    private val heardMs: Long = 0,
    private val notHeardMs: Long = 0,
    private val thoroughRunMs: Long = 0,
    /** Level at which the person taps the calibration ramp, per volume index (default: -56 at every index). */
    private val tapAt: (Int) -> Double = { -56.0 },
    /** The session to answer for (the ViewModel creates its own). */
    private val sessionProvider: (() -> TestSession)? = null,
) {
    private fun sess(): TestSession = sessionProvider?.invoke() ?: rig.session

    /** UI tests answer some Quick trials by clicking; set false to leave trials open. */
    @Volatile var autoQuick = true

    /** Thorough runs are confirmed automatically; false leaves them to the test. */
    @Volatile var autoThorough = true

    /** The first N Quick trials are left open (a UI test answers them by clicking). */
    @Volatile var leaveOpen = 0
    private val opened = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var wrongEarFirst = false
    private var channelAnswers = 0
    private var runBusy = false
    val answeredQuick = java.util.concurrent.atomic.AtomicInteger(0)

    fun install() {
        rig.tone.onPlay = { b -> react(b) }
        if (rig.mode == TestMode.THOROUGH) installThorough()
    }

    private fun react(b: StereoBuffer) {
        val st = sess().state.value
        val info = b.info()
        when {
            // Answers count only from 300 ms after the step's first beep: a person answers the repeat.
            st is SessionState.ChannelCheck && sess().channelAnswerArmed -> {
                val correct = if (info.ear == Ear.LEFT) ChannelAnswer.LEFT else ChannelAnswer.RIGHT
                val wrong = if (correct == ChannelAnswer.LEFT) ChannelAnswer.RIGHT else ChannelAnswer.LEFT
                sess().answerChannel(if (wrongEarFirst && channelAnswers++ == 0) wrong else correct)
            }
            st is SessionState.Calibrating && st.phase == CalibrationPhase.RAMP ->
                if (!info.silent && info.levelDbfs >= tapAt(rig.volume.index()) - 0.01) sess().calibrationTap()
            st is SessionState.Testing && st.mode == TestMode.QUICK && st.trialOpen && autoQuick && opened.getAndIncrement() >= leaveOpen -> {
                val session = sess()
                // The screen no longer shows the trial's frequency before the answer: read it from the session.
                val heard = responder.respond(quickTrialOf(info, session.quickTrialFreq))
                answeredQuick.incrementAndGet()
                val delayMs = if (heard) heardMs else notHeardMs
                val trial = session.quickTrialId
                // A person answers no earlier than the minimum reaction time; answer exactly this trial (a trial that
                // was interrupted meanwhile is never answered late into the next one).
                (scope ?: rig.scope).launch {
                    if (delayMs > 0) delay(delayMs)
                    session.armedTrial.first { it >= trial }
                    if (session.armedTrial.value == trial) session.respond(heard)
                }
            }
        }
    }

    private fun installThorough() {
        val sc = checkNotNull(scope) { "Thorough needs a scope for the run delay" }
        sc.launch {
            sess().state.collect { st ->
                if (st is SessionState.Testing && st.dial != null && !runBusy && autoThorough) {
                    runBusy = true
                    launch { handleRun(st) }
                } else if (st is SessionState.Testing && st.dial == null) {
                    runBusy = false
                }
            }
        }
    }

    private suspend fun handleRun(st: SessionState.Testing) {
        val dial = st.dial!!
        val before = rig.tone.played.size
        while (rig.tone.played.size == before && sess().isActive) delay(10)
        val info = rig.tone.played.last().info()
        val level0 = info.levelDbfs
        val ear = info.ear ?: Ear.LEFT
        if (thoroughRunMs > 0) delay(thoroughRunMs)
        while (!sess().dialArmed && sess().isActive) delay(10) // the minimum reaction time after the first pulse
        val target = listener.thoroughConfirmLevel(ear, st.frequencyHz)
        val desired = dial.position + (target - level0).roundToInt()
        sess().dialTo(desired)
        val now = (sess().state.value as? SessionState.Testing)?.dial
        if (now != null && ((desired > dial.maxPosition && now.atTop) || (desired < dial.minPosition && now.atBottom))) {
            sess().dialLimit()
        } else {
            sess().dialConfirm()
        }
    }
}

/** Typical person: true thresholds near `L_exp` for `lRef = -60`. */
fun normalListener(seed: Long = 7, rel: (Int) -> Double = { 0.0 }) =
    SimulatedListener(truthAround(-60.0, seed, rel), Random(seed * 31 + 1))

fun PointKey.str() = "${ear}@$freqHz"
