package my.umarh.paudio.feature.health

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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

    private fun show(state: HealthUiState) {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) { HealthScreenContent(state, onBack = {}) }
        }
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
}
