package my.umarh.paudio.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.umarh.paudio.audio.effect.EngineError
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M12 accessibility pass (SPEC §8): labelled clickables and 48 dp touch targets on Home, in both themes. */
@RunWith(AndroidJUnit4::class)
class HomeA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private val profile = HomeProfile(
        profileId = 1,
        profileName = "Buds",
        listenerName = "Me",
        headset = HeadsetLabel.Named("Test Buds"),
        strength = 0.5f,
        butterfly = null,
        hasTest = true,
    )

    private var current by mutableStateOf(HomeUiState())

    private fun show(dark: Boolean) {
        rule.setContent { PAudioTheme(darkTheme = dark) { HomeContent(current, HomeActions()) } }
    }

    private val states = mapOf(
        "profile" to HomeUiState(
            loading = false, running = true, engineState = EngineState.Waiting,
            profile = profile, engineFollowsProfile = true, personalized = true,
        ),
        "empty" to HomeUiState(loading = false, running = true),
        "conflictRetry" to HomeUiState(
            loading = false, running = true, engineState = EngineState.Conflict,
            profile = profile, engineFollowsProfile = true,
        ),
        "errorRetry" to HomeUiState(
            loading = false, running = true, engineState = EngineState.Error(EngineError.CREATION_FAILED),
            profile = profile, engineFollowsProfile = true,
        ),
        "serviceOff" to HomeUiState(loading = false, running = false, profile = profile),
    )

    private fun audit(dark: Boolean) {
        show(dark)
        states.forEach { (_, s) ->
            current = s
            rule.waitForIdle()
            rule.assertClickablesAreLabelledAndLargeEnough()
        }
    }

    @Test
    fun everyClickableIsLabelledAndAtLeast48dp_lightTheme() = audit(dark = false)

    @Test
    fun everyClickableIsLabelledAndAtLeast48dp_darkTheme() = audit(dark = true)
}
