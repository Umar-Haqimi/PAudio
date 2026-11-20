package my.umarh.paudio.feature.health

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.designsystem.PAudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val disclaimer = ApplicationProvider.getApplicationContext<Context>().getString(R.string.health_disclaimer)

    /** Items: info, audiogram, right, left, grade, asymmetry, trend, how-to-read button (last index 7). */
    private val lastIndex = 7

    private fun content(withTrend: Boolean): HealthUiState.Content {
        val t = test(2)
        val th = thresholds(2, right = { if (it == 4000) 9.0 else 0.0 }, left = { if (it in 4000..6000) 30.0 else 0.0 })
        val trend = if (withTrend) {
            listOfNotNull(HealthMapper.trendPoint(test(1), thresholds(1)), HealthMapper.trendPoint(t, th))
        } else {
            emptyList()
        }
        return HealthUiState.Content(HealthMapper.content(t, th, trend)!!)
    }

    private fun show(state: HealthUiState, onShowLatest: () -> Unit = {}) {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                HealthScreenContent(state, onBack = {}, onShowLatest = onShowLatest)
            }
        }
    }

    /** A test with every note: asymmetry (left 30 dB worse from 2 kHz up), unreliable, one low-confidence and one censored point. */
    private fun contentWithNotes(): HealthUiState.Content {
        val t = test(2, reliable = false)
        val th = thresholds(
            2,
            left = { if (it >= 2000) 30.0 else 0.0 },
            censoredAt = setOf(Ear.LEFT to 14000),
            lowConfidenceAt = setOf(Ear.RIGHT to 4000),
        )
        return HealthUiState.Content(HealthMapper.content(t, th, emptyList())!!)
    }

    private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

    private fun assertNoDismissActions(node: SemanticsNode) {
        val c = node.config
        assertThat(c.contains(SemanticsActions.OnClick)).isFalse()
        assertThat(c.contains(SemanticsActions.OnLongClick)).isFalse()
        assertThat(c.contains(SemanticsActions.Dismiss)).isFalse()
        assertThat(c.contains(SemanticsActions.Expand)).isFalse()
        assertThat(c.contains(SemanticsActions.Collapse)).isFalse()
    }

    private fun assertDisclaimerIsPlain() {
        rule.onNodeWithText(disclaimer).assertIsDisplayed()
        val box = rule.onNodeWithTag(DISCLAIMER_TAG).fetchSemanticsNode()
        subtree(box).forEach(::assertNoDismissActions)
        var p: SemanticsNode? = box.parent
        while (p != null) {
            assertNoDismissActions(p)
            p = p.parent
        }
    }

    @Test
    fun disclaimer_displayedFully_noActions_andStaysWhenScrolledToEnd() {
        show(content(withTrend = true))
        assertDisclaimerIsPlain()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(lastIndex)
        rule.waitForIdle()
        assertDisclaimerIsPlain()
    }

    @Test
    fun disclaimer_visibleInEmptyState() {
        show(HealthUiState.Empty)
        assertDisclaimerIsPlain()
    }

    @Test
    fun disclaimer_visibleWhileLoading() {
        show(HealthUiState.Loading)
        rule.onNodeWithText(disclaimer).assertIsDisplayed()
    }

    @Test
    fun emptyState_showsExplanation() {
        show(HealthUiState.Empty)
        rule.onNodeWithText("No hearing test yet").assertIsDisplayed()
    }

    @Test
    fun content_showsBothEarsAndTrend() {
        show(content(withTrend = true))
        // right PTA3 = 9 / 3 = 3; left PTA3 = 30 / 3 = 10
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(2)
        rule.onNodeWithText("Right ear").assertExists()
        rule.onNodeWithText("About 3 dB (estimated)").assertExists()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(3)
        rule.onNodeWithText("Left ear").assertExists()
        rule.onNodeWithText("About 10 dB (estimated)").assertExists()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(4)
        rule.onNodeWithText("Approximately normal range (estimated)").assertExists()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(lastIndex)
        rule.onNodeWithText("Trend over time").assertExists()
        rule.onNodeWithText("Take another test with the same headphones to see a trend.").assertDoesNotExist()
    }

    @Test
    fun content_withOneTest_explainsHowToGetATrend() {
        show(content(withTrend = false))
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(lastIndex)
        rule.onNodeWithText("Take another test with the same headphones to see a trend.").assertExists()
    }

    @Test
    fun howToRead_opensSheet() {
        show(content(withTrend = false))
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(lastIndex)
        rule.onNodeWithText("How to read this").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("What this cannot show").assertExists()
    }

    @Test
    fun howToRead_sheetKeepsFullDisclaimerVisible_withNoActions() {
        show(content(withTrend = false))
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(lastIndex)
        rule.onNodeWithText("How to read this").performClick()
        rule.waitForIdle()
        val inSheet = hasText(disclaimer) and hasAnyAncestor(hasTestTag(SHEET_DISCLAIMER_TAG))
        rule.onNode(inSheet).assertIsDisplayed()
        val box = rule.onNodeWithTag(SHEET_DISCLAIMER_TAG).fetchSemanticsNode()
        subtree(box).forEach(::assertNoDismissActions)
        // The disclaimer's own wrappers (up to the sheet's content column) must not be actionable either.
        var p: SemanticsNode? = box.parent
        while (p != null && p.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } != SHEET_TAG) {
            assertNoDismissActions(p)
            p = p.parent
        }
    }

    @Test
    fun asymmetryReliabilityAndCensoredNotes_areShown() {
        val state = contentWithNotes()
        assertThat(state.content.asymmetry).isTrue()
        assertThat(state.content.reliability.unreliable).isTrue()
        assertThat(state.content.reliability.lowConfidencePoints).isEqualTo(1)
        assertThat(state.content.anyCensored).isTrue()
        show(state)
        // items: info 0, audiogram 1, right 2, left 3, grade 4, asymmetry 5, reliability 6, trend 7, button 8
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(1)
        rule.onNodeWithText("Points marked with a down arrow", substring = true).assertExists()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(5)
        rule.onNodeWithText("Ears differ").assertExists()
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(6)
        rule.onNodeWithText("Less reliable").assertExists()
        rule.onNodeWithText("You pressed the button during some silent moments", substring = true).assertExists()
        rule.onNodeWithText("1 point was inconsistent between repeats", substring = true).assertExists()
    }

    @Test
    fun notes_areAbsentForCleanTest() {
        show(HealthUiState.Content(HealthMapper.content(test(2), thresholds(2), emptyList())!!))
        rule.onNodeWithTag(CONTENT_TAG).performScrollToIndex(1)
        rule.onNodeWithText("Points marked with a down arrow", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Less reliable").assertDoesNotExist()
        rule.onNodeWithText("Ears differ").assertDoesNotExist()
    }

    @Test
    fun unavailable_showsMessage_keepsDisclaimer_andOffersShowLatest() {
        var clicked = 0
        show(HealthUiState.Unavailable(UnavailableReason.TEST_MISSING, canShowLatest = true), onShowLatest = { clicked++ })
        rule.onNodeWithText("This test is no longer available.").assertIsDisplayed()
        assertDisclaimerIsPlain()
        rule.onNodeWithText("Show latest test").performClick()
        assertThat(clicked).isEqualTo(1)
    }

    @Test
    fun unavailable_withoutLatest_hasNoAction() {
        show(HealthUiState.Unavailable(UnavailableReason.PROFILE_MISSING))
        rule.onNodeWithText("This profile is no longer available.").assertIsDisplayed()
        rule.onNodeWithText("Show latest test").assertDoesNotExist()
    }

    @Test
    fun incomplete_hasIncompleteMessage_notNoTestYet() {
        show(HealthUiState.Unavailable(UnavailableReason.INCOMPLETE))
        rule.onNodeWithText("This test's data is incomplete, so it can't be shown.").assertIsDisplayed()
        rule.onNodeWithText("No hearing test yet").assertDoesNotExist()
    }

    @Test
    fun testInfo_isNeutral() {
        show(content(withTrend = false))
        rule.onNode(hasText("Test from", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Latest test", substring = true).assertDoesNotExist()
    }

    @Test
    fun formatDb_truncatesToOneDecimal_neverRoundsUpAcrossGrade_andNeverPrintsMinusZero() {
        val en = java.util.Locale.US
        assertThat(formatDb(19.6, en)).isEqualTo("19.6")
        assertThat(formatDb(19.99, en)).isEqualTo("19.9")
        assertThat(formatDb(3.0, en)).isEqualTo("3")
        assertThat(formatDb(2.9999999999, en)).isEqualTo("3")
        assertThat(formatDb(-0.04, en)).isEqualTo("0")
        assertThat(formatDb(-0.0, en)).isEqualTo("0")
        assertThat(formatDb(-2.55, en)).isEqualTo("-2.5")
        assertThat(formatDb(19.6, java.util.Locale.GERMANY)).isEqualTo("19,6")
    }
}
