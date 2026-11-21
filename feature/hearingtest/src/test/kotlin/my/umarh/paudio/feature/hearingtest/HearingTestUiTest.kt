package my.umarh.paudio.feature.hearingtest

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import kotlin.random.Random

private class RecordingActions : HearingTestActions {
    val calls = mutableListOf<String>()
    override fun chooseMode(mode: TestMode) { calls += "chooseMode:$mode" }
    override fun backToModeChoice() { calls += "backToModeChoice" }
    override fun setAncOn(on: Boolean) { calls += "anc:$on" }
    override fun onNoisePermissionResult(granted: Boolean) { calls += "noise:$granted" }
    override fun disableNoiseMeter() { calls += "noiseOff" }
    override fun startTest() { calls += "start" }
    override fun answerChannel(answer: ChannelAnswer) { calls += "channel:$answer" }
    override fun calibrationTap() { calls += "tap" }
    override fun manualVolumeDone() { calls += "manual" }
    override fun respond(heard: Boolean) { calls += "respond:$heard" }
    override fun dialTo(position: Int) { calls += "dial:$position" }
    override fun dialConfirm() { calls += "confirm" }
    override fun dialLimit() { calls += "limit" }
    override fun resume() { calls += "resume" }
    override fun redoTest() { calls += "redo" }
    override fun keepGoing() { calls += "keep" }
    override fun abort() { calls += "abort" }
    override fun tryAgain() { calls += "tryAgain" }
}

@RunWith(AndroidJUnit4::class)
class HearingTestUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)

    private fun show(state: HearingTestUiState, actions: HearingTestActions = RecordingActions(), onBack: () -> Unit = {}) {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                HearingTestContent(state, false, MutableStateFlow<NoiseLevel?>(null), emptyFlow(), actions, onBack)
            }
        }
    }

    private fun prep(blockers: List<PrepBlocker> = emptyList(), noise: NoiseMeterState = NoiseMeterState.OFFERED) =
        HearingTestUiState.Prep(TestMode.QUICK, ancOn = false, dnd = DndStatus.OFF, blockers = blockers, someChecksUnknown = false, noise = noise)

    @Test
    fun preTestPageShowsInstructionsAndABlockingWarningDisablesStart() {
        val actions = RecordingActions()
        show(prep(listOf(PrepBlocker.Mono, PrepBlocker.NoHeadphones)), actions)
        rule.onNodeWithText(str(R.string.hearingtest_prep_quiet)).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_prep_anc)).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_prep_dnd)).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_blocker_mono)).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_blocker_no_headphones)).assertExists()
        rule.onNodeWithTag(Tags.START).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.START).performClick()
        assertThat(actions.calls).doesNotContain("start")
    }

    @Test
    fun startIsEnabledWithoutBlockersAndNoiseCardIsHiddenWhenDenied() {
        val actions = RecordingActions()
        show(prep(), actions)
        rule.onNodeWithTag(Tags.START).assertIsEnabled()
        rule.onNodeWithTag(Tags.NOISE_OFFER).assertExists()
        rule.onNodeWithTag(Tags.START).performScrollTo().performClick()
        assertThat(actions.calls).contains("start")
        rule.onNodeWithTag(Tags.ANC).performScrollTo().performClick()
        assertThat(actions.calls).contains("anc:true")
    }

    @Test
    fun deniedMicrophoneHidesTheNoiseCard() {
        show(prep(noise = NoiseMeterState.DENIED))
        rule.onNodeWithTag(Tags.NOISE_OFFER).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.hearingtest_prep_noise_heading)).assertDoesNotExist()
    }

    private fun pauseShows(reason: PauseReason, copy: String) {
        show(
            HearingTestUiState.Active(
                SessionState.Paused(reason, setOf(reason), canResume = false, volumeKeysNeeded = false, calibrating = false),
            ),
        )
        rule.onNodeWithText(copy).assertExists()
        if (reason != PauseReason.UNRELIABLE) rule.onNodeWithTag(Tags.RESUME).assertIsNotEnabled()
    }

    @Test fun pausedHeadsetCopy() = pauseShows(PauseReason.HEADSET_DISCONNECTED, "Your earphones disconnected. Reconnect them to continue.")

    @Test fun pausedVolumeCopy() =
        pauseShows(PauseReason.VOLUME_CHANGED, "The volume changed, so we'll redo this tone. Please don't touch the volume during the test.")

    @Test fun pausedFocusCopy() = pauseShows(PauseReason.FOCUS_LOST, "Paused for another app's audio.")

    @Test fun pausedBackgroundCopy() = pauseShows(PauseReason.BACKGROUNDED, "Paused while PAudio was in the background.")

    @Test fun pausedUnreliableCopy() = pauseShows(PauseReason.UNRELIABLE, "Some answers looked like guesses. Want to redo the test?")

    @Test
    fun pauseAndAbortCopyMatchesTheSpecVerbatim() {
        assertThat(str(R.string.hearingtest_paused_headset)).isEqualTo("Your earphones disconnected. Reconnect them to continue.")
        assertThat(str(R.string.hearingtest_paused_volume))
            .isEqualTo("The volume changed, so we'll redo this tone. Please don't touch the volume during the test.")
        assertThat(str(R.string.hearingtest_paused_focus)).isEqualTo("Paused for another app's audio.")
        assertThat(str(R.string.hearingtest_paused_background)).isEqualTo("Paused while PAudio was in the background.")
        assertThat(str(R.string.hearingtest_paused_unreliable)).isEqualTo("Some answers looked like guesses. Want to redo the test?")
        assertThat(str(R.string.hearingtest_channel_wrong)).isEqualTo("Your earbuds may be swapped or in mono mode")
        assertThat(str(R.string.hearingtest_aborted_cant_hear)).isEqualTo("Can't hear anything? Check connection")
        assertThat(str(R.string.hearingtest_aborted_volume_range))
            .isEqualTo("Your volume range doesn't suit testing. Try other earphones or check the connection.")
        assertThat(context.getString(R.string.hearingtest_mode_quick_duration, 6, 8)).isEqualTo("≈ 6–8 min")
    }

    @Test
    fun backDuringATestAsksForConfirmation() {
        val actions = RecordingActions()
        var backs = 0
        val testing = SessionState.Testing(TestMode.QUICK, 0.3f, 5, 18, 4000, trialOpen = true, dial = null, calibrationWarning = false)
        show(HearingTestUiState.Active(testing), actions) { backs++ }
        rule.onNodeWithTag(Tags.HEAR).assertIsEnabled()
        rule.onNodeWithTag(Tags.DIDNT).assertIsEnabled()
        rule.onNodeWithText(str(R.string.hearingtest_quick_didnt)).assertExists()
        rule.onNodeWithContentDescriptionBack().performClick()
        rule.onNodeWithTag(Tags.EXIT_DIALOG).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_exit_keep)).performClick()
        assertThat(backs).isEqualTo(0)
        rule.onNodeWithContentDescriptionBack().performClick()
        rule.onNodeWithText(str(R.string.hearingtest_exit_stop)).performClick()
        assertThat(actions.calls).contains("abort")
        assertThat(backs).isEqualTo(1)
    }

    /** Posted-from-background work (ViewModel collectors, recomposition) only runs when the paused main looper is idled. */
    private fun pumpMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionBack() =
        onNodeWithContentDescription(str(R.string.hearingtest_back))

    @Test
    fun quickHappyPathThroughTheRealViewModel() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "paudio-hearingtest-ui").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val rig = Rig(TestMode.QUICK, { System.nanoTime() / 1_000_000 }, dispatcher, timed = false, seed = 12)
        val env = my.umarh.paudio.testing.FakeEnvironmentChecks()
        val vm = HearingTestViewModel(
            requestedProfileId = null,
            deps = rig.deps,
            env = env,
            noise = my.umarh.paudio.testing.FakeNoiseMeter(),
            dispatchers = SessionDispatcherFactory { dispatcher },
            randomSource = { Random(12) },
        )
        val brain = Brain(rig, normalListener(), sessionProvider = { vm.currentSession!! })
        brain.leaveOpen = 3 // the first three trials are answered by clicking the hear button
        brain.install()
        val opened = java.util.concurrent.CopyOnWriteArrayList<HearingTestEvent>()
        val signals = java.util.concurrent.atomic.AtomicInteger(0)
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                val state by vm.uiState.collectAsState()
                val playing by vm.stimulusPlaying.collectAsState()
                LaunchedEffect(Unit) { vm.events.collect { opened += it } }
                LaunchedEffect(Unit) { vm.signals.collect { signals.incrementAndGet() } }
                HearingTestContent(state, playing, vm.noiseLevel, vm.signals, vm, {})
            }
        }
        fun dump() = "state=${vm.uiState.value} session=${vm.currentSession?.state?.value} err=${vm.currentSession?.lastFailure} played=${rig.tone.played.size} signals=${signals.get()}"
        try {
        rule.onNodeWithTag(Tags.MODE_QUICK).performClick()
        rule.waitUntil(10_000) { pumpMain(); rule.onAllNodes(hasTestTag(Tags.START) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(Tags.START).performScrollTo().performClick()
        repeat(3) { n ->
            rule.waitUntil(30_000) { pumpMain(); rule.onAllNodes(hasTestTag(Tags.HEAR) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(Tags.HEAR).performClick()
            rule.waitUntil(30_000) { pumpMain(); signals.get() > n }
        }
        rule.waitUntil(120_000) { pumpMain(); opened.isNotEmpty() }
        val id = (opened.single() as HearingTestEvent.OpenResults).testId
        assertThat(id).isEqualTo(42L)
        assertThat(rig.store.saved.single().thresholds).hasSize(18)
        assertThat(rig.tone.violations).isEmpty()
        assertThat(rig.trace.openCount).isEqualTo(0)
        assertThat(rig.tone.isOpen).isFalse()
        dispatcher.close()
        } catch (e: Throwable) { throw AssertionError(dump(), e) }
    }
}
