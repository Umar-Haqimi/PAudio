package my.umarh.paudio.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on every onboarding step. */
@RunWith(AndroidJUnit4::class)
class OnboardingA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private var current by mutableStateOf(OnboardingUiState())

    private fun audit(dark: Boolean) {
        rule.setContent {
            PAudioTheme(darkTheme = dark) {
                OnboardingContent(
                    state = current, permissionNeeded = true, onNext = {}, onBack = {}, onAcknowledge = {},
                    onRequestPermission = {}, onNameChange = {}, onFinish = {},
                )
            }
        }
        for (step in OnboardingStep.entries) {
            current = OnboardingUiState(step = step, disclaimerAccepted = step == OnboardingStep.LISTENER)
            rule.waitForIdle()
            rule.assertClickablesAreLabelledAndLargeEnough()
        }
        current = OnboardingUiState(step = OnboardingStep.DISCLAIMER, disclaimerAccepted = true)
        rule.waitForIdle()
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun everyStep_lightTheme() = audit(dark = false)

    @Test
    fun everyStep_darkTheme() = audit(dark = true)
}
