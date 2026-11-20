package my.umarh.paudio.feature.results

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class Calls {
        var personalized: Boolean? = null
        var health: Pair<Long, Long>? = null
        var preference: Long? = null
        var done = 0
        var redo = 0
        var play = 0
        var picked = 0
    }

    private val calls = Calls()
    private var ab by mutableStateOf(
        AbUiState(status = AbStatus.READY, gLeft = DoubleArray(240) { 3.0 }, gRight = DoubleArray(240) { 2.0 }),
    )

    private fun content(
        reliable: Boolean = true,
        asym: Boolean = false,
        censored: Boolean = false,
        lowConf: Boolean = false,
    ): ResultsUiState.Content {
        val th = thresholds(
            1,
            left = { if (asym && it >= 4000) 30.0 else 0.0 },
            censoredAt = if (censored) setOf(Ear.RIGHT to 14000) else emptySet(),
            lowConfidenceAt = if (lowConf) setOf(Ear.LEFT to 3000) else emptySet(),
        )
        return ResultsUiState.Content(ResultsMapper.content(test(1, profileId = 5, reliable = reliable), th)!!)
    }

    private fun show(ui: ResultsUiState, withRedo: Boolean = true) {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                ResultsScreenContent(
                    ui = ui,
                    ab = ab,
                    onSetPersonalized = {
                        calls.personalized = it
                        ab = ab.copy(personalized = it)
                    },
                    onTogglePlay = { calls.play++ },
                    onPickFile = { calls.picked++ },
                    onUseDemo = {},
                    onRetryAb = {},
                    onSeeHealth = { p, t -> calls.health = p to t },
                    onPreferenceTest = { calls.preference = it },
                    onDone = { calls.done++ },
                    onRedoTest = if (withRedo) ({ calls.redo++ }) else null,
                )
            }
        }
    }

    private fun descriptionOf(substring: String): String =
        rule.onNode(hasContentDescription(substring, substring = true, ignoreCase = true)).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].first()

    @Test
    fun summary_usesEstimatedPlainWords_perEar() {
        show(content())
        rule.onNodeWithText("What we estimated").performScrollTo().assertIsDisplayed()
        val estimated = rule.onAllNodes(hasText("About 0 dB (estimated)", substring = true)).fetchSemanticsNodes()
        assertThat(estimated).isNotEmpty()
        val words = rule.onAllNodes(hasText("Estimated close to your reference level", substring = true)).fetchSemanticsNodes()
        assertThat(words).isNotEmpty()
    }

    @Test
    fun butterfly_hasATalkBackDescriptionWithBothEars() {
        show(content())
        val d = descriptionOf("Hearing butterfly")
        assertThat(d).contains("Right ear:")
        assertThat(d).contains("Left ear:")
        assertThat(d).contains("(estimated)")
    }

    @Test
    fun toggle_switchesLabelAndGraphState() {
        show(content())
        rule.onNodeWithText("Listening to: Personalized").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("This is how Personalized changes the sound for each ear.").assertExists()
        val personalizedGraph = descriptionOf("EQ")

        rule.onNodeWithText("Original").performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(calls.personalized).isFalse()
        rule.onNodeWithText("Listening to: Original").assertExists()
        rule.onNodeWithText("Original is flat: nothing is changed.").assertExists()
        // The graph's own TalkBack description switches to the flat state.
        assertThat(descriptionOf("EQ")).isNotEqualTo(personalizedGraph)

        rule.onNodeWithText("Personalized").performScrollTo().performClick()
        rule.waitForIdle()
        assertThat(calls.personalized).isTrue()
        rule.onNodeWithText("Listening to: Personalized").assertExists()
        assertThat(descriptionOf("EQ")).isEqualTo(personalizedGraph)
    }

    @Test
    fun playButton_isDisabledUntilReady_andPlays() {
        ab = AbUiState(status = AbStatus.PREPARING)
        show(content())
        rule.onNodeWithTag(PLAY_TAG).performScrollTo().assertIsNotEnabled()
        ab = AbUiState(status = AbStatus.READY)
        rule.waitForIdle()
        rule.onNodeWithTag(PLAY_TAG).assertIsEnabled().performClick()
        assertThat(calls.play).isEqualTo(1)
    }

    @Test
    fun ownSong_buttonLaunchesPicker_andSourceIsShown() {
        show(content())
        rule.onNodeWithText("Demo clip: Discipline").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Use my own song").performScrollTo().performClick()
        assertThat(calls.picked).isEqualTo(1)
        ab = ab.copy(sourceKind = AbSourceKind.USER_FILE, fileName = "my song.mp3")
        rule.waitForIdle()
        rule.onNodeWithText("Your song: my song.mp3").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun notes_unreliableOffersRedo_asymmetryAndBeyondRange() {
        show(content(reliable = false, asym = true, censored = true, lowConf = true))
        rule.onNodeWithText("Less reliable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Test again").performScrollTo().performClick()
        assertThat(calls.redo).isEqualTo(1)
        rule.onNodeWithText("Ears differ").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("consider seeing an audiologist", substring = true).assertExists()
        rule.onNodeWithText("Beyond test range").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Some high pitches in your right ear were beyond the test range", substring = true).assertExists()
        rule.onNodeWithText("1 point was inconsistent between repeats", substring = true).assertExists()
    }

    @Test
    fun notes_absentWhenNothingToSay() {
        show(content())
        rule.onNodeWithText("Good to know").assertDoesNotExist()
    }

    @Test
    fun seeHealth_passesProfileAndTest() {
        show(content())
        rule.onNodeWithText("See hearing health").performScrollTo().performClick()
        assertThat(calls.health).isEqualTo(5L to 1L)
    }

    @Test
    fun systemBack_showsExitDialog_thenPreferenceTest() {
        show(content())
        rule.onNodeWithTag(EXIT_DIALOG_TAG).assertDoesNotExist()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(EXIT_DIALOG_TAG).assertIsDisplayed()
        rule.onNodeWithText("Fine-tune to your taste next?").assertIsDisplayed()
        assertThat(calls.done).isEqualTo(0)
        rule.onNodeWithText("Fine-tune now").performClick()
        assertThat(calls.preference).isEqualTo(5L)
        assertThat(calls.done).isEqualTo(0)
    }

    @Test
    fun doneButton_showsExitDialog_andNotNowLeaves() {
        show(content())
        rule.onNodeWithText("Done").performScrollTo().performClick()
        rule.onNodeWithTag(EXIT_DIALOG_TAG).assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        assertThat(calls.done).isEqualTo(1)
        assertThat(calls.preference).isNull()
        rule.onNodeWithTag(EXIT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun backWithoutResults_leavesDirectly() {
        show(ResultsUiState.Unavailable(UnavailableReason.TEST_MISSING))
        rule.onNodeWithText("This test is no longer available.").assertIsDisplayed()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertThat(calls.done).isEqualTo(1)
        rule.onNodeWithTag(EXIT_DIALOG_TAG).assertDoesNotExist()
    }
}
