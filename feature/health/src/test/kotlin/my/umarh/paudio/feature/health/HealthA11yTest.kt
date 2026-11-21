package my.umarh.paudio.feature.health

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on Hearing health, in both themes. */
@RunWith(AndroidJUnit4::class)
class HealthA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private fun state(): HealthUiState.Content {
        val t = test(2, reliable = false)
        val th = thresholds(
            2,
            left = { if (it >= 2000) 30.0 else 0.0 },
            censoredAt = setOf(Ear.LEFT to 14000),
            lowConfidenceAt = setOf(Ear.RIGHT to 4000),
        )
        val trend = listOfNotNull(HealthMapper.trendPoint(test(1), thresholds(1)), HealthMapper.trendPoint(t, th))
        return HealthUiState.Content(HealthMapper.content(t, th, trend)!!)
    }

    private fun audit(dark: Boolean) {
        rule.setContent {
            PAudioTheme(darkTheme = dark) { HealthScreenContent(state(), onBack = {}, onShowLatest = {}) }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun health_lightTheme() = audit(dark = false)

    @Test
    fun health_darkTheme() = audit(dark = true)

    @Test
    fun unavailable_offersLabelledAction() {
        rule.setContent {
            PAudioTheme(darkTheme = true) { HealthScreenContent(HealthUiState.Unavailable(UnavailableReason.TEST_MISSING, canShowLatest = true), onBack = {}, onShowLatest = {}) }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }
}
