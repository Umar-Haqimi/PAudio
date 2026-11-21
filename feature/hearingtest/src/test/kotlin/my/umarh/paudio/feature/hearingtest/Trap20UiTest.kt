package my.umarh.paudio.feature.hearingtest

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private class RecordingHaptics : HapticFeedback {
    val events = mutableListOf<HapticFeedbackType>()
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        events += hapticFeedbackType
    }
}

/**
 * SPEC §2.2 trap #20, UI side (DECISIONS §27): haptics and TalkBack announcements fire only while the stimulus flag
 * is false. With `Trap20SessionTest` (the flag drops in every gap; signals are emitted in silence) this pins the trap
 * behaviourally for Quick and Thorough.
 */
@RunWith(AndroidJUnit4::class)
class Trap20UiTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun dialTickIsDeferredToTheSilentGapAndNeverFiresDuringAPulse() {
        val haptics = RecordingHaptics()
        var position by mutableIntStateOf(10)
        var playing by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                    LevelDial(position, 0, 40, playing, onPositionChange = { position = it })
                }
            }
        }
        rule.waitForIdle()
        // detents crossed during a pulse: nothing vibrates
        position = 11
        rule.waitForIdle()
        position = 12
        rule.waitForIdle()
        assertThat(haptics.events).isEmpty()
        // the gap starts: exactly one tick
        playing = false
        rule.waitForIdle()
        assertThat(haptics.events).hasSize(1)
        // in the gap a detent ticks at once
        position = 13
        rule.waitForIdle()
        assertThat(haptics.events).hasSize(2)
        // next pulse: nothing, and nothing is left pending for the next gap from before
        playing = true
        rule.waitForIdle()
        playing = false
        rule.waitForIdle()
        assertThat(haptics.events).hasSize(2)
    }

    @Test
    fun responseHapticAndAnnouncementWaitForSilence() {
        val haptics = RecordingHaptics()
        val signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 4)
        var playing by mutableStateOf(true)
        val testing = SessionState.Testing(TestMode.QUICK, 0.3f, 5, 18, 4000, trialOpen = true, dial = null, calibrationWarning = false)
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                    HearingTestContent(
                        HearingTestUiState.Active(testing), playing, MutableStateFlow<NoiseLevel?>(null), signals,
                        RecordingActions(), {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val heard = context.getString(R.string.hearingtest_ack_heard)
        // a signal that reaches the UI while a stimulus is audible waits
        signals.tryEmit(SessionSignal.Responded(heard = true))
        rule.waitForIdle()
        assertThat(haptics.events).isEmpty()
        rule.onNodeWithContentDescription(heard).assertDoesNotExist()
        playing = false
        rule.waitForIdle()
        assertThat(haptics.events).hasSize(1)
        rule.onNodeWithContentDescription(heard).assertExists()
    }
}
