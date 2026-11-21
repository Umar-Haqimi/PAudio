package my.umarh.paudio.feature.hearingtest

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.audiometry.RunKind
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility pass for the hearing-test flow (SPEC §8, DECISIONS §26.4 pattern): on every screen of the flow, in
 * both themes, each clickable node has a speakable label and a touch target of at least 48 dp.
 */
@RunWith(AndroidJUnit4::class)
class HearingTestA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun audit(state: HearingTestUiState, dark: Boolean) {
        rule.setContent {
            PAudioTheme(darkTheme = dark) {
                HearingTestContent(state, false, MutableStateFlow<NoiseLevel?>(NoiseLevel.QUIET), emptyFlow(), RecordingActions(), {})
            }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    private val prep = HearingTestUiState.Prep(
        TestMode.QUICK, ancOn = false, dnd = DndStatus.UNKNOWN,
        blockers = listOf(PrepBlocker.Mono, PrepBlocker.WrongHeadset("Buds")), someChecksUnknown = true,
        noise = NoiseMeterState.OFFERED,
    )

    private fun active(s: SessionState) = HearingTestUiState.Active(s)

    private val quick = SessionState.Testing(TestMode.QUICK, 0.3f, 5, 18, 4000, trialOpen = true, dial = null, calibrationWarning = true)
    private val thorough = SessionState.Testing(
        TestMode.THOROUGH, 0.5f, 9, 18, 2000, trialOpen = true,
        dial = DialUi(minPosition = 0, maxPosition = 40, position = 40, kind = RunKind.DESCENDING, atTop = true, atBottom = false),
        calibrationWarning = false,
    )

    @Test fun modeChoice_light() = audit(HearingTestUiState.ModeChoice(null), dark = false)

    @Test fun modeChoice_dark() = audit(HearingTestUiState.ModeChoice(TestMode.QUICK), dark = true)

    @Test fun prep_light() = audit(prep, dark = false)

    @Test fun prep_dark() = audit(prep.copy(noise = NoiseMeterState.ENABLED), dark = true)

    @Test fun channelCheck_light() = audit(active(SessionState.ChannelCheck(1, 2, ChannelCheckProblem.WRONG_EAR)), dark = false)

    @Test fun channelCheck_dark() = audit(active(SessionState.ChannelCheck(2, 2, ChannelCheckProblem.TOO_QUIET)), dark = true)

    @Test fun calibration_light() = audit(active(SessionState.Calibrating(CalibrationPhase.RAMP, 1, canTap = true, volumeRefused = true)), dark = false)

    @Test fun calibrationManualVolume_dark() =
        audit(active(SessionState.Calibrating(CalibrationPhase.MANUAL_VOLUME, 1, canTap = false, direction = VolumeDirection.UP)), dark = true)

    @Test fun quick_light() = audit(active(quick), dark = false)

    @Test fun quick_dark() = audit(active(quick.copy(trialOpen = false)), dark = true)

    @Test fun thorough_light() = audit(active(thorough), dark = false)

    @Test fun thorough_dark() = audit(active(thorough.copy(dial = thorough.dial!!.copy(position = 0, atTop = false, atBottom = true, kind = RunKind.ASCENDING))), dark = true)

    @Test fun paused_light() =
        audit(active(SessionState.Paused(PauseReason.VOLUME_CHANGED, setOf(PauseReason.VOLUME_CHANGED), canResume = true, volumeKeysNeeded = true, calibrating = false)), dark = false)

    @Test fun pausedUnreliable_dark() =
        audit(active(SessionState.Paused(PauseReason.UNRELIABLE, setOf(PauseReason.UNRELIABLE), canResume = true, volumeKeysNeeded = false, calibrating = false, testFinished = true)), dark = true)

    @Test fun aborted_light() = audit(active(SessionState.Aborted(AbortReason.CANT_HEAR)), dark = false)

    @Test fun saveFailed_light() = audit(active(SessionState.SaveFailed(profileMissing = false)), dark = false)

    @Test fun saveFailedProfileMissing_dark() = audit(active(SessionState.SaveFailed(profileMissing = true)), dark = true)
}
