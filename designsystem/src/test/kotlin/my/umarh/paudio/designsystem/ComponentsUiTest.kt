package my.umarh.paudio.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.designsystem.chart.Audiogram
import my.umarh.paudio.designsystem.chart.AudiogramPoint
import my.umarh.paudio.designsystem.chart.EqGraph
import my.umarh.paudio.designsystem.chart.HearingButterfly
import my.umarh.paudio.designsystem.chart.TrendChart
import my.umarh.paudio.designsystem.chart.TrendPoint
import my.umarh.paudio.designsystem.chart.WhoBandLegend
import my.umarh.paudio.designsystem.component.DisclaimerCard
import my.umarh.paudio.designsystem.component.InstructionRow
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.SegmentedToggle
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone
import my.umarh.paudio.designsystem.hear.FrequencyWaveProgress
import my.umarh.paudio.designsystem.hear.MorphingHearButton
import androidx.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val Freqs = listOf(1000, 2000, 4000, 8000, 14000)

@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun describedBy(vararg parts: String): Boolean =
        rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().any { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> parts.all { d.contains(it) } } == true
        }

    private val dark = mutableStateOf(false)

    private fun setThemed(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false // components may run infinite animations
        rule.setContent { PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = dark.value) { content() } }
        rule.mainClock.advanceTimeBy(300)
    }

    private fun forBothThemes(check: () -> Unit = {}) {
        dark.value = false
        rule.mainClock.advanceTimeBy(100)
        check()
        dark.value = true
        rule.mainClock.advanceTimeBy(100)
        check()
    }

    @Test
    fun butterfly_exposesCallerDescription() {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = true) {
                HearingButterfly(
                    leftHl = listOf(0f, 5f, 10f, 20f, null), rightHl = listOf(0f, 2f, 8f, 15f, 30f),
                    frequenciesHz = Freqs, contentDescription = "Butterfly summary", animate = false,
                )
            }
        }
        rule.onNodeWithContentDescription("Butterfly summary").assertExists()
    }

    @Test
    fun butterfly_miniAndLight_compose() {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                HearingButterfly(
                    leftHl = listOf(0f, 5f, 10f, 20f, 30f), rightHl = listOf(0f, 2f, 8f, 15f, 30f),
                    frequenciesHz = Freqs, mini = true, contentDescription = "Mini",
                )
            }
        }
        rule.onNodeWithContentDescription("Mini").assertExists()
    }

    @Test
    fun audiogram_listsValuesInSemantics_bothThemes() {
        setThemed {
            Audiogram(
                rightEar = Freqs.map { AudiogramPoint(it, 10f, censored = it == 14000) },
                leftEar = Freqs.map { AudiogramPoint(it, 20f, lowConfidence = it == 4000) },
            )
            WhoBandLegend()
        }
        forBothThemes {
            assertThat(describedBy("Right ear", "14 kilohertz", "low confidence", "not heard at the loudest")).isTrue()
            rule.onNodeWithText("Normal (below 20 dB)").assertExists()
        }
    }

    @Test
    fun eqGraph_composes_bothThemes_andNullEars() {
        val grid = doubleArrayOf(20.0, 100.0, 1000.0, 8000.0, 20000.0)
        setThemed { EqGraph(doubleArrayOf(0.0, 1.0, 3.0, 9.0, 12.0), null, grid, personalized = dark.value) }
        forBothThemes {
            assertThat(describedBy("Equalizer graph")).isTrue()
        }
    }

    @Test
    fun trendChart_emptyStateBelowTwoPoints() {
        setThemed { TrendChart(listOf(TrendPoint(1_700_000_000_000, 5f, 6f))) }
        forBothThemes {
            rule.onNodeWithText("Take another test to see how your results change over time.").assertExists()
        }
    }

    @Test
    fun trendChart_withThreePoints_exposesDescription() {
        setThemed {
            TrendChart(
                listOf(
                    TrendPoint(1_700_000_000_000, 5f, 6f),
                    TrendPoint(1_710_000_000_000, 7f, null),
                    TrendPoint(1_720_000_000_000, 9f, 10f),
                ),
            )
        }
        forBothThemes { assertThat(describedBy("across 3 tests", "Right ear, latest")).isTrue() }
    }

    @Test
    fun waveProgress_composes() {
        setThemed { FrequencyWaveProgress(progress = 0.3f, frequencyHz = 8000, stimulusPlaying = true) }
        forBothThemes()
    }

    @Test
    fun hearButton_firesOnPress_viaSemanticsClick_only() {
        var presses = 0
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                MorphingHearButton(onPress = { presses++ }, stimulusPlaying = false, enabled = true)
            }
        }
        rule.onNodeWithText("I heard it").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(presses).isEqualTo(1)
    }

    @Test
    fun hearButton_disabled_doesNotFire() {
        var presses = 0
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = true) {
                MorphingHearButton(onPress = { presses++ }, stimulusPlaying = false, enabled = false)
            }
        }
        rule.onNodeWithText("I heard it").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(presses).isEqualTo(0)
    }

    @Test
    fun disclaimerCard_hasNoClickableOrDismissNode() {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) { DisclaimerCard("Not a medical device.") }
        }
        rule.onNodeWithText("Not a medical device.").assertExists()
        val nodes = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        nodes.forEach { n ->
            assertThat(n.config.contains(SemanticsActions.OnClick)).isFalse()
            assertThat(n.config.contains(SemanticsActions.Dismiss)).isFalse()
            assertThat(n.config.contains(SemanticsActions.Expand)).isFalse()
            assertThat(n.config.contains(SemanticsActions.Collapse)).isFalse()
        }
    }

    @Test
    fun smallComponents_compose_bothThemes() {
        setThemed {
            SectionCard(title = "Title") {
                StatusChip("Ok", StatusTone.Ok, icon = PAudioIcons.Check)
                InstructionRow("Turn off noise cancellation", PAudioIcons.GraphicEqOff)
                SegmentedToggle(personalized = true, onPersonalizedChange = {})
                Text("x")
            }
        }
        forBothThemes { rule.onNodeWithText("Personalized").assertExists() }
    }
}
