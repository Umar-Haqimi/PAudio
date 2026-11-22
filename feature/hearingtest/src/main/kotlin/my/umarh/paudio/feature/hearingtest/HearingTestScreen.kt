package my.umarh.paudio.feature.hearingtest

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons

/**
 * Type-safe navigation destination of the hearing-test flow (DECISIONS §23).
 *
 * [profileId] `null` = the active listener's profile for the CURRENT headset: the listener (a default one when none
 * exists yet), the headset and the profile are created when the test is saved. Non-null = retest that profile; the
 * connected headset must be the profile's own. Speaker / non-processable outputs are always blocked with copy.
 */
@Serializable
data class HearingTestRoute(val profileId: Long? = null)

/**
 * The hearing-test flow (F2–F8). [onShowResults] receives the id of the freshly saved test and is called only after
 * the stream was closed, the volume restored and the engine bypass released.
 */
fun NavGraphBuilder.hearingTestScreen(onShowResults: (testId: Long) -> Unit, onBack: () -> Unit) {
    composable<HearingTestRoute> {
        HearingTestScreen(onShowResults = onShowResults, onBack = onBack)
    }
}

/** What the screens can ask for; implemented by [HearingTestViewModel], faked in UI tests. */
interface HearingTestActions {
    fun chooseMode(mode: TestMode)
    fun backToModeChoice()
    fun setAncOn(on: Boolean)
    fun onNoisePermissionResult(granted: Boolean)
    fun disableNoiseMeter()
    fun startTest()
    fun answerChannel(answer: ChannelAnswer)
    fun calibrationTap()
    fun manualVolumeDone()
    fun respond(heard: Boolean)
    fun dialTo(position: Int)
    fun dialConfirm()
    fun dialLimit()
    fun resume()
    fun redoTest()
    fun keepGoing()
    fun abort()
    fun tryAgain()
    fun retrySave()
    fun saveToActiveProfile()
}

internal object Tags {
    const val MODE_QUICK = "ht_mode_quick"
    const val MODE_THOROUGH = "ht_mode_thorough"
    const val PREP = "ht_prep"
    const val START = "ht_start"
    const val BLOCKERS = "ht_blockers"
    const val NOISE_OFFER = "ht_noise_offer"
    const val NOISE_LEVEL = "ht_noise_level"
    const val ANC = "ht_anc"
    const val CHANNEL_LEFT = "ht_channel_left"
    const val CHANNEL_RIGHT = "ht_channel_right"
    const val CHANNEL_BOTH = "ht_channel_both"
    const val CHANNEL_LOUDER = "ht_channel_louder"
    const val CAL_TAP = "ht_cal_tap"
    const val HEAR = "ht_hear"
    const val DIDNT = "ht_didnt"
    const val DIAL = "ht_dial"
    const val DIAL_CONFIRM = "ht_dial_confirm"
    const val PAUSED = "ht_paused"
    const val RESUME = "ht_resume"
    const val ABORTED = "ht_aborted"
    const val SAVE_FAILED = "ht_save_failed"
    const val SAVE_RETRY = "ht_save_retry"
    const val SAVE_ACTIVE = "ht_save_active"
    const val EXIT_DIALOG = "ht_exit_dialog"
    const val LEAVE_UNSAVED_DIALOG = "ht_leave_unsaved_dialog"
}

@Composable
internal fun HearingTestScreen(
    onShowResults: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: HearingTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playing by viewModel.stimulusPlaying.collectAsStateWithLifecycle()
    PauseWhenBackgrounded(onStopped = viewModel::onStopped, onStarted = viewModel::onStarted)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { e ->
                when (e) {
                    is HearingTestEvent.OpenResults -> onShowResults(e.testId)
                }
            }
        }
    }
    HearingTestContent(
        state = state,
        stimulusPlaying = playing,
        noiseLevel = viewModel.noiseLevel,
        signals = viewModel.signals,
        actions = viewModel,
        onBack = onBack,
        // Read live at fire time from the session (not the recomposed copy, which lags if the main thread stalls).
        isStimulusAudibleNow = viewModel::isStimulusAudibleNow,
    )
}

/**
 * Backgrounding pauses the test (F8); ON_STOP also covers screen-off. A configuration change (rotation) also stops
 * the activity, but the ViewModel and its session survive it: that must not pause the test. If this composable first
 * runs while its lifecycle is already below STARTED (the recreated activity was stopped before it composed, e.g. the
 * screen went off right after a rotation), that missed ON_STOP is delivered at once.
 */
@Composable
internal fun PauseWhenBackgrounded(onStopped: () -> Unit, onStarted: () -> Unit) {
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStopped)
    val start by rememberUpdatedState(onStarted)
    DisposableEffect(owner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) stop()
                Lifecycle.Event.ON_START -> start()
                else -> Unit
            }
        }
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
internal fun HearingTestContent(
    state: HearingTestUiState,
    stimulusPlaying: Boolean,
    noiseLevel: StateFlow<NoiseLevel?>,
    signals: Flow<SessionSignal>,
    actions: HearingTestActions,
    onBack: () -> Unit,
    /** Live "is a stimulus audible now" read at haptic/announcement time; defaults to [stimulusPlaying]. */
    isStimulusAudibleNow: (() -> Boolean)? = null,
) {
    SuppressSoundEffects()
    var showExit by remember { mutableStateOf(false) }
    var showLeaveUnsaved by remember { mutableStateOf(false) }
    val session = (state as? HearingTestUiState.Active)?.session
    val terminal = session?.isAfterCleanup == true

    val handleBack: () -> Unit = {
        when {
            // The finished test is only in memory: leaving discards it, so ask first.
            session is SessionState.SaveFailed -> showLeaveUnsaved = true
            state is HearingTestUiState.ModeChoice || terminal -> onBack()
            state is HearingTestUiState.Prep -> actions.backToModeChoice()
            else -> showExit = true
        }
    }
    BackHandler(onBack = handleBack)

    // Trap #20: haptics and accessibility announcements only AFTER a response, when the stimulus has stopped. The
    // session emits signals only in silence; the UI additionally waits for silence in case a signal is collected
    // late (after the next stimulus started), so neither ever coincides with a stimulus.
    val haptics = LocalHapticFeedback.current
    var ack by remember { mutableStateOf("") }
    val heardText = stringResource(R.string.hearingtest_ack_heard)
    val notHeardText = stringResource(R.string.hearingtest_ack_not_heard)
    val confirmedText = stringResource(R.string.hearingtest_ack_confirmed)
    val playingNow by rememberUpdatedState(stimulusPlaying)
    val liveAudible by rememberUpdatedState(isStimulusAudibleNow)
    LaunchedEffect(signals) {
        signals.collect { s ->
            snapshotFlow { playingNow }.first { !it }
            // The recomposed flag can lag (a stalled main thread): re-check the live source right before firing.
            while (liveAudible?.invoke() == true) delay(LIVE_SILENCE_POLL_MS)
            val text = when (s) {
                is SessionSignal.Responded -> {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (s.heard) heardText else notHeardText
                }
                SessionSignal.RunConfirmed -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    confirmedText
                }
            }
            // Blank for one frame first, so the same text twice is still announced. Nothing here is tied to a
            // trial onset (the old "clear when the next trial opens" was a trial-locked semantics change).
            ack = ""
            withFrameNanos { }
            ack = text
        }
    }

    TestPage(
        title = stringResource(R.string.feature_hearingtest_title),
        onBack = handleBack,
    ) {
        when (state) {
            is HearingTestUiState.ModeChoice -> ModeChoiceScreen(actions)
            is HearingTestUiState.Prep -> PrepScreen(state, noiseLevel, actions)
            is HearingTestUiState.Active -> SessionScreen(state.session, stimulusPlaying, actions, onBack)
        }
        // Announced politely, and only ever changed after a response (never while a stimulus plays).
        Box(
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = ack
                },
        )
    }

    if (showLeaveUnsaved) {
        AlertDialog(
            modifier = Modifier.testTag(Tags.LEAVE_UNSAVED_DIALOG),
            onDismissRequest = { showLeaveUnsaved = false },
            title = { Text(stringResource(R.string.hearingtest_leave_unsaved_title)) },
            text = { Text(stringResource(R.string.hearingtest_leave_unsaved_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveUnsaved = false
                        onBack()
                    },
                ) { Text(stringResource(R.string.hearingtest_leave_unsaved_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveUnsaved = false }) { Text(stringResource(R.string.hearingtest_leave_unsaved_stay)) }
            },
        )
    }

    if (showExit) {
        AlertDialog(
            modifier = Modifier.testTag(Tags.EXIT_DIALOG),
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.hearingtest_exit_title)) },
            text = { Text(stringResource(R.string.hearingtest_exit_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExit = false
                        actions.abort()
                        onBack()
                    },
                ) { Text(stringResource(R.string.hearingtest_exit_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.hearingtest_exit_keep)) }
            },
        )
    }
}

private const val LIVE_SILENCE_POLL_MS = 16L

/** UI click sounds would contaminate the test (trap #20): off for the whole flow while composed. */
@Composable
internal fun SuppressSoundEffects() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        onDispose { view.isSoundEffectsEnabled = previous }
    }
}

@Composable
internal fun TestPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                PAudioIcon(PAudioIcons.ArrowBack, contentDescription = stringResource(R.string.hearingtest_back))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { heading() },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
