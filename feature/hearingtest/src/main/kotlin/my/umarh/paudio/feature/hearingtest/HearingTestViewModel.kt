package my.umarh.paudio.feature.hearingtest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.umarh.paudio.audio.device.EnvWarning
import my.umarh.paudio.audio.device.EnvironmentVerdict
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.EnvSnapshot
import my.umarh.paudio.core.model.EnvironmentChecks
import my.umarh.paudio.core.model.NoiseMeter
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.TestMode
import java.io.Closeable
import javax.inject.Inject
import kotlin.random.Random

enum class DndStatus { ON, OFF, UNKNOWN }

/** Why the Start button is disabled on the pre-test page (SPEC §2.2 #11: wrong-ear data is meaningless). */
sealed interface PrepBlocker {
    data object Mono : PrepBlocker

    data object Balance : PrepBlocker

    data object Spatializer : PrepBlocker

    /** Speaker, earpiece, HDMI or no output: never tested. */
    data object NoHeadphones : PrepBlocker

    data class WrongHeadset(val displayName: String) : PrepBlocker

    data object ProfileMissing : PrepBlocker
}

/** The optional microphone noise meter (SPEC §2.1 F2, §6). */
enum class NoiseMeterState {
    /** Not asked yet: show the opt-in button. */
    OFFERED,

    /** Permission granted: the meter runs while the page is composed. */
    ENABLED,

    /** Permission denied: the whole noise card is hidden. */
    DENIED,
}

sealed interface HearingTestUiState {
    data class ModeChoice(val selected: TestMode?) : HearingTestUiState

    data class Prep(
        val mode: TestMode,
        val ancOn: Boolean,
        val dnd: DndStatus,
        val blockers: List<PrepBlocker>,
        /** Some environment checks could not be read on this device (non-blocking caution). */
        val someChecksUnknown: Boolean,
        val noise: NoiseMeterState,
    ) : HearingTestUiState {
        val canStart: Boolean get() = blockers.isEmpty()
    }

    data class Active(val session: SessionState) : HearingTestUiState
}

sealed interface HearingTestEvent {
    data class OpenResults(val testId: Long) : HearingTestEvent
}

private sealed interface Stage {
    data object ModeChoice : Stage

    data class Prep(val mode: TestMode) : Stage

    data class Running(val session: TestSession) : Stage
}

/**
 * Owns the pre-test state and the running [TestSession]. The session runs on a dedicated single-thread dispatcher
 * in a scope owned by this ViewModel; [onCleared] cancels it (its `finally` closes the AudioTrack, restores the
 * volume and releases the bypass token) and closes the dispatcher once the session has finished its cleanup.
 */
@HiltViewModel
class HearingTestViewModel internal constructor(
    private val requestedProfileId: Long?,
    private val deps: SessionDeps,
    private val env: EnvironmentChecks,
    private val noise: NoiseMeter,
    private val dispatchers: SessionDispatcherFactory,
    private val randomSource: () -> Random,
) : ViewModel(), HearingTestActions {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        deps: SessionDeps,
        env: EnvironmentChecks,
        noise: NoiseMeter,
        dispatchers: SessionDispatcherFactory,
    ) : this(
        requestedProfileId = savedStateHandle.toRoute<HearingTestRoute>().profileId,
        deps = deps,
        env = env,
        noise = noise,
        dispatchers = dispatchers,
        randomSource = { Random.Default },
    )

    private val stage = MutableStateFlow<Stage>(Stage.ModeChoice)
    private val selectedMode = MutableStateFlow<TestMode?>(null)
    private val ancOn = MutableStateFlow(false)
    private val noiseState = MutableStateFlow(NoiseMeterState.OFFERED)
    private var session: TestSession? = null
    private var lastMode: TestMode = TestMode.QUICK

    private val _events = Channel<HearingTestEvent>(Channel.BUFFERED)
    val events: Flow<HearingTestEvent> = _events.receiveAsFlow()

    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 16)

    /** Haptic / announcement triggers; emitted only after a stimulus has stopped (SPEC §2.2 #20). */
    val signals: SharedFlow<SessionSignal> = _signals

    /** True while a stimulus is audible: pulses the hear button; no haptics while true. */
    val stimulusPlaying: StateFlow<Boolean> get() = deps.tone.playing

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HearingTestUiState> = stage
        .flatMapLatest { s ->
            when (s) {
                Stage.ModeChoice -> selectedMode.map { HearingTestUiState.ModeChoice(it) }
                is Stage.Prep -> prepFlow(s.mode)
                is Stage.Running -> s.session.state.map { HearingTestUiState.Active(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HearingTestUiState.ModeChoice(null))

    /**
     * Smoothed noise class of the room. The flow only runs while somebody collects it (the pre-test page, with
     * `collectAsStateWithLifecycle`): when the page leaves composition the AudioRecord is released at once
     * (`stopTimeout = 0`). Readings are reduced to a class in memory; nothing is stored or logged.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val noiseLevel: StateFlow<NoiseLevel?> = noiseState
        .flatMapLatest { if (it == NoiseMeterState.ENABLED) smoothedNoise() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    private fun smoothedNoise(): Flow<NoiseLevel?> = noise.levels()
        .scan(emptyList<Float>()) { window, v -> (window + v).takeLast(NOISE_WINDOW) }
        .map { w -> if (w.isEmpty()) null else NoiseLevel.classify(w.average().toFloat()) }

    private fun envSnapshots(): Flow<EnvSnapshot> = flow {
        while (true) {
            emit(env.snapshot())
            delay(ENV_POLL_MS)
        }
    }

    private fun prepFlow(mode: TestMode): Flow<HearingTestUiState.Prep> =
        combine(envSnapshots(), deps.devices.current, ancOn, noiseState) { snap, device, anc, ns ->
            PrepInputs(snap, device, anc, ns)
        }.map { p ->
            val verdict = EnvironmentVerdict.of(p.snapshot)
            HearingTestUiState.Prep(
                mode = mode,
                ancOn = p.anc,
                dnd = when {
                    p.snapshot.dndOn == null -> DndStatus.UNKNOWN
                    p.snapshot.dndOn == true -> DndStatus.ON
                    else -> DndStatus.OFF
                },
                blockers = blockersOf(verdict, p.device),
                someChecksUnknown = verdict.unknown.any { it != my.umarh.paudio.audio.device.EnvCheck.DND },
                noise = p.noise,
            )
        }

    private class PrepInputs(val snapshot: EnvSnapshot, val device: OutputDevice?, val anc: Boolean, val noise: NoiseMeterState)

    private suspend fun blockersOf(verdict: EnvironmentVerdict, device: OutputDevice?): List<PrepBlocker> = buildList {
        for (w in verdict.blocking) {
            add(
                when (w) {
                    EnvWarning.MONO_ON -> PrepBlocker.Mono
                    EnvWarning.BALANCE_OFF_CENTER -> PrepBlocker.Balance
                    EnvWarning.SPATIALIZER_ON -> PrepBlocker.Spatializer
                },
            )
        }
        when (val t = deps.store.check(device, requestedProfileId)) {
            TargetCheck.Ok -> Unit
            TargetCheck.NotProcessable -> add(PrepBlocker.NoHeadphones)
            is TargetCheck.WrongHeadset -> add(PrepBlocker.WrongHeadset(t.displayName))
            TargetCheck.ProfileMissing -> add(PrepBlocker.ProfileMissing)
        }
    }

    // ---- pre-test inputs -------------------------------------------------------------------------------------

    override fun chooseMode(mode: TestMode) {
        selectedMode.value = mode
        lastMode = mode
        stage.value = Stage.Prep(mode)
    }

    override fun backToModeChoice() {
        stage.value = Stage.ModeChoice
    }

    override fun setAncOn(on: Boolean) {
        ancOn.value = on
    }

    /** Result of the RECORD_AUDIO request the user started from the noise card. */
    override fun onNoisePermissionResult(granted: Boolean) {
        noiseState.value = if (granted) NoiseMeterState.ENABLED else NoiseMeterState.DENIED
    }

    override fun disableNoiseMeter() {
        noiseState.value = NoiseMeterState.OFFERED
    }

    /** Re-validates the page (the environment may have changed since the last poll) and starts the session. */
    override fun startTest() {
        val prep = stage.value as? Stage.Prep ?: return
        if (session != null) return
        viewModelScope.launch {
            val verdict = EnvironmentVerdict.of(env.snapshot())
            if (blockersOf(verdict, deps.devices.current.value).isNotEmpty()) return@launch
            launchSession(prep.mode)
        }
    }

    private fun launchSession(mode: TestMode) {
        val dispatcher = dispatchers.create()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val s = TestSession(
            config = SessionConfig(mode, ancOn.value, requestedProfileId, randomSource()),
            deps = deps,
            scope = scope,
        )
        session = s
        stage.value = Stage.Running(s)
        viewModelScope.launch { s.signals.collect { _signals.tryEmit(it) } }
        viewModelScope.launch {
            s.state.collect { st -> if (st is SessionState.Finished) _events.trySend(HearingTestEvent.OpenResults(st.testId)) }
        }
        val job = s.start()
        // Close the dispatcher only AFTER the session's finally (stream closed, volume restored, token released).
        job.invokeOnCompletion { (dispatcher as? Closeable)?.close() }
    }

    // ---- session inputs --------------------------------------------------------------------------------------

    override fun answerChannel(answer: ChannelAnswer) = session?.answerChannel(answer) ?: Unit

    override fun calibrationTap() = session?.calibrationTap() ?: Unit

    override fun manualVolumeDone() = session?.manualVolumeDone() ?: Unit

    override fun respond(heard: Boolean) = session?.respond(heard) ?: Unit

    override fun dialTo(position: Int) = session?.dialTo(position) ?: Unit

    override fun dialConfirm() = session?.dialConfirm() ?: Unit

    override fun dialLimit() = session?.dialLimit() ?: Unit

    override fun resume() = session?.resume() ?: Unit

    override fun redoTest() = session?.redoTest() ?: Unit

    override fun keepGoing() = session?.keepGoing() ?: Unit

    fun onStopped() = session?.onBackgrounded() ?: Unit

    fun onStarted() = session?.onForegrounded() ?: Unit

    /** Back / exit confirmed: tears the session down (cleanup runs on the session thread). */
    override fun abort() {
        session?.cancel()
    }

    /** After an abort: back to the pre-test page of the same mode. */
    override fun tryAgain() {
        session?.cancel()
        session = null
        stage.value = Stage.Prep(lastMode)
    }

    override fun onCleared() {
        // Process/screen teardown: the cancellation runs the session's finally (close track, restore volume,
        // release the bypass token). The dispatcher is closed by the job's completion handler afterwards.
        session?.cancel()
        session = null
    }

    private companion object {
        const val ENV_POLL_MS = 1_000L
        const val NOISE_WINDOW = 8
    }
}
