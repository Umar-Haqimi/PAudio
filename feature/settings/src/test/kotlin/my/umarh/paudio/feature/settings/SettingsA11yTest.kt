package my.umarh.paudio.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on Settings and About. */
@RunWith(AndroidJUnit4::class)
class SettingsA11yTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun settings_lightTheme() {
        rule.setContent { PAudioTheme(darkTheme = false) { SettingsContent(SettingsUiState(loading = false), SettingsActions()) } }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun settings_darkTheme() {
        rule.setContent {
            PAudioTheme(darkTheme = true) { SettingsContent(SettingsUiState(loading = false, serviceRunning = true), SettingsActions()) }
        }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }

    @Test
    fun about_darkTheme() {
        rule.setContent { PAudioTheme(darkTheme = true) { AboutContent(versionName = "1.0", onBack = {}) } }
        rule.assertClickablesAreLabelledAndLargeEnough()
    }
}
