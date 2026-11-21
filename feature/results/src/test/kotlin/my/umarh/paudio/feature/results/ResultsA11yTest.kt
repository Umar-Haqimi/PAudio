package my.umarh.paudio.feature.results

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on Results, in both themes. */
@RunWith(AndroidJUnit4::class)
class ResultsA11yTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun content(): ResultsUiState.Content {
        val th = thresholds(
            1,
            left = { if (it >= 4000) 30.0 else 0.0 },
            censoredAt = setOf(Ear.RIGHT to 14000),
            lowConfidenceAt = setOf(Ear.LEFT to 3000),
        )
        return ResultsUiState.Content(ResultsMapper.content(test(1, profileId = 5, reliable = false), th)!!)
    }

    private fun audit(dark: Boolean) {
        rule.setContent {
            PAudioTheme(darkTheme = dark) {
                ResultsScreenContent(
                    ui = content(),
                    ab = AbUiState(status = AbStatus.READY, gLeft = DoubleArray(240) { 3.0 }, gRight = DoubleArray(240) { 2.0 }),
                    onSetPersonalized = {}, onTogglePlay = {}, onPickFile = {}, onUseDemo = {}, onRetryAb = {},
                    onSeeHealth = { _, _ -> }, onPreferenceTest = {}, onDone = {}, onRedoTest = {},
                )
            }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun results_lightTheme() = audit(dark = false)

    @Test
    fun results_darkTheme() = audit(dark = true)
}
