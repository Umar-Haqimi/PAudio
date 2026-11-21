package my.umarh.paudio.feature.preference

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.dsp.CurveBuilder
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val baseline = PreferenceBaseline(1, DoubleArray(9) { it * 4.0 }, DoubleArray(9) { it * 4.0 }, true, 0.5, 0.0, 0.0, 0.0, true)

    private var ui by mutableStateOf(PreferenceUiState(phase = PrefPhase.ROUND, roundNumber = 1, axis = my.umarh.paudio.core.preference.PreferenceAxis.BASS))
    private val chosen = mutableListOf<AbSide>()
    private var cantTell = 0

    private fun setContent(actions: PreferenceActions) {
        rule.setContent {
            PAudioTheme { PreferenceScreenContent(ui, actions) }
        }
    }

    @Test
    fun chooseButtonsAreDisabledUntilTheGateOpens() {
        setContent(PreferenceActions(onBack = {}, onChoose = { s, _ -> chosen += s }, onCantTell = { cantTell++ }))
        rule.onNodeWithTag(CHOOSE_A_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(CHOOSE_B_TAG).assertIsNotEnabled()
        // "Can't tell" is always available.
        rule.onNodeWithTag(CANT_TELL_TAG).performScrollTo().assertIsEnabled().performClick()
        assertThat(cantTell).isEqualTo(1)

        ui = ui.copy(aHeard = true, bHeard = false, canChoose = false)
        rule.onNodeWithTag(CHOOSE_A_TAG).assertIsNotEnabled()

        ui = ui.copy(aHeard = true, bHeard = true, canChoose = true)
        rule.onNodeWithTag(CHOOSE_A_TAG).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag(CHOOSE_B_TAG).performScrollTo().assertIsEnabled().performClick()
        assertThat(chosen).containsExactly(AbSide.A, AbSide.B).inOrder()
    }

    @Test
    fun gateProgressIsSpelledOutInWords() {
        setContent(PreferenceActions(onBack = {}))
        rule.onNodeWithTag(CHOOSE_A_TAG).assertIsDisplayed()
        ui = ui.copy(aHeard = true)
        rule.onNodeWithText("A: heard", substring = true).assertExists()
        rule.onNodeWithText("B: keep listening", substring = true).assertExists()
    }

    @Test
    fun fineTuneSlidersUpdateTheGraphState() {
        // Drive the real ViewModel logic through the content: sliders -> callbacks -> new state with a new curve.
        var state = PreferenceUiState(phase = PrefPhase.FINE_TUNE, strength = 0.5f)
        fun curveOf(s: PreferenceUiState) = CurveBuilder.build(
            baseline.hlLeft, baseline.hlRight, s.strength.toDouble(), s.gB.toDouble(), s.gP.toDouble(), s.gT.toDouble(),
        ).let { s.copy(gLeft = it.gLeft, gRight = it.gRight) }
        ui = curveOf(state)
        val before = ui.gLeft!!.toList()
        val seen = mutableListOf<Float>()
        setContent(
            PreferenceActions(
                onBack = {},
                onBass = { v ->
                    seen += v
                    ui = curveOf(ui.copy(gB = v))
                },
                onStrength = { v -> ui = curveOf(ui.copy(strength = v)) },
            ),
        )
        rule.onNodeWithTag(SLIDER_BASS_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SLIDER_BASS_TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        rule.waitForIdle()
        assertThat(seen).contains(9f)
        assertThat(ui.gB).isEqualTo(9f)
        assertThat(ui.gLeft!!.toList()).isNotEqualTo(before)
        rule.onNodeWithTag(EQ_GRAPH_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SLIDER_STRENGTH_TAG).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.waitForIdle()
        assertThat(ui.strength).isEqualTo(1f)
    }

    @Test
    fun resultShowsValuesInPlainWords() {
        ui = PreferenceUiState(phase = PrefPhase.RESULT, gB = 3f, gP = -2.5f, gT = 0f, gLeft = DoubleArray(240), gRight = DoubleArray(240))
        setContent(PreferenceActions(onBack = {}))
        rule.onNodeWithText("Bass: +3 dB, more bass").assertExists()
        rule.onNodeWithText("Voices and instruments: −2.5 dB, voices and instruments set back").assertExists()
        rule.onNodeWithText("Treble: 0 dB, no change").assertExists()
    }
}
