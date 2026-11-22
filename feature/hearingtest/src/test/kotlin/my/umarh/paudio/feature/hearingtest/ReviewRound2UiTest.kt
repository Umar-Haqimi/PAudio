package my.umarh.paudio.feature.hearingtest

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

private class CountingHaptics : HapticFeedback {
    val events = mutableListOf<HapticFeedbackType>()
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        events += hapticFeedbackType
    }
}

/** Second review round of M9a, UI side (DECISIONS §27.1). */
@RunWith(AndroidJUnit4::class)
class ReviewRound2UiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = context.getString(id)

    // ---- Quick: no trial-locked visual or semantics change; buttons stay enabled -------------------------------

    @Test
    fun quickHearButtonAndScreenAreIdenticalBeforeOnsetDuringTheStimulusAndInTheGap() {
        val beforeOnset = SessionState.Testing(TestMode.QUICK, 0.3f, 5, 18, 2000, trialOpen = false, dial = null, calibrationWarning = false)
        var state by mutableStateOf<SessionState>(beforeOnset)
        var playing by mutableStateOf(false)
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                HearingTestContent(HearingTestUiState.Active(state), playing, MutableStateFlow<NoiseLevel?>(null), emptyFlow(), RecordingActions(), {})
            }
        }
        fun snapshot(): String {
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.HEAR).assertIsEnabled()
            rule.onNodeWithTag(Tags.DIDNT).assertIsEnabled()
            return rule.onNodeWithTag(Tags.HEAR).printToString(maxDepth = 5) + "\n" +
                rule.onRoot().printToString(maxDepth = 50)
        }
        val before = snapshot()
        // onset: the session opens the trial and the stimulus is under the head
        state = beforeOnset.copy(trialOpen = true)
        playing = true
        val during = snapshot()
        // the gap after the answer
        state = beforeOnset
        playing = false
        val gap = snapshot()
        assertThat(during).isEqualTo(before)
        assertThat(gap).isEqualTo(before)
    }

    // ---- SHOULD-FIX 2: Back on SaveFailed asks first ----------------------------------------------------------

    @Test
    fun backOnSaveFailedAsksBeforeDiscardingTheResults() {
        var backs = 0
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                HearingTestContent(
                    HearingTestUiState.Active(SessionState.SaveFailed(profileMissing = false)), false,
                    MutableStateFlow<NoiseLevel?>(null), emptyFlow(), RecordingActions(), { backs++ },
                )
            }
        }
        // system Back
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag(Tags.LEAVE_UNSAVED_DIALOG).assertExists()
        assertThat(backs).isEqualTo(0)
        rule.onNodeWithText(str(R.string.hearingtest_leave_unsaved_stay)).performClick()
        rule.onNodeWithTag(Tags.LEAVE_UNSAVED_DIALOG).assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
        // top-bar Back, then Leave
        rule.onNodeWithContentDescription(str(R.string.hearingtest_back)).performClick()
        rule.onNodeWithTag(Tags.LEAVE_UNSAVED_DIALOG).assertExists()
        rule.onNodeWithText(str(R.string.hearingtest_leave_unsaved_leave)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    // ---- NIT: signal haptics read the live flag at fire time ----------------------------------------------------

    @Test
    fun signalHapticWaitsForTheLiveFlagEvenWhenTheRecomposedOneIsStale() {
        val haptics = CountingHaptics()
        val signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 4)
        var liveAudible = true // the session says a stimulus is audible; the recomposed flag (false) is stale
        val testing = SessionState.Testing(TestMode.QUICK, 0.3f, 5, 18, 4000, trialOpen = false, dial = null, calibrationWarning = false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                    HearingTestContent(
                        HearingTestUiState.Active(testing), false, MutableStateFlow<NoiseLevel?>(null), signals,
                        RecordingActions(), {}, isStimulusAudibleNow = { liveAudible },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        signals.tryEmit(SessionSignal.Responded(heard = true))
        rule.mainClock.advanceTimeBy(500)
        assertThat(haptics.events).isEmpty()
        liveAudible = false
        rule.mainClock.advanceTimeBy(100)
        assertThat(haptics.events).hasSize(1)
    }

    // ---- NIT pins: LevelDial drag batching, rotation ------------------------------------------------------------

    @Test
    fun dialDragKeepsEveryDetentWhenEventsArriveBeforeRecomposition() {
        var position by mutableIntStateOf(10)
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                LevelDial(position, 0, 40, stimulusPlaying = false, onPositionChange = { position = it }, modifier = Modifier.height(320.dp).testTag("dial"))
            }
        }
        val density = context.resources.displayMetrics.density
        val detentPx = (320f - 48f) * density / 40f
        rule.onNodeWithTag("dial").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f * density)) // past the touch slop
            repeat(6) { moveBy(Offset(0f, -detentPx * 1.01f)) } // six one-detent moves in one batch, no recomposition
            up()
        }
        rule.waitForIdle()
        // the six batched moves alone are six detents; the stale-position bug kept only the last one
        assertThat(position - 10).isAtLeast(6)
    }

    @Test
    fun rotationDoesNotPauseButARealStopDoes() {
        var stops = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { PauseWhenBackgrounded(onStopped = { stops++ }, onStarted = {}) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.recreate() // configuration change: the old activity stops while changing configurations
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertThat(stops).isEqualTo(0)

        val other = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        other.get().setContent { PauseWhenBackgrounded(onStopped = { stops++ }, onStarted = {}) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        other.pause().stop()
        assertThat(stops).isEqualTo(1)
    }
}
