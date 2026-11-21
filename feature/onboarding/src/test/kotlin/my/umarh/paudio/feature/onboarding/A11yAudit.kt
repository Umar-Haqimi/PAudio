package my.umarh.paudio.feature.onboarding

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat

/**
 * M12 accessibility audit helper (identical copy in each feature module's tests; DECISIONS §26):
 * every node with a click action must expose a label a screen reader can speak (text, content description, an
 * editable value or the click label) and a layout size of at least 48 dp x 48 dp. The check is deliberately
 * strict: it measures the clickable node itself, not its touch target. A default Material `IconButton` (40 dp) already
 * gets a 48 dp touch target from `minimumInteractiveComponentSize()` touch-bounds expansion, so a node that fails
 * here is not necessarily untappable; the screens still size such buttons to 48 dp so that the visible bounds match.
 * The unclipped `size` is used because `touchBoundsInRoot` is empty for nodes scrolled out of view.
 */
internal fun SemanticsNodeInteractionsProvider.assertClickablesAreLabelledAndLargeEnough(
    minDp: Float = 48f,
    allowSmall: (label: String) -> Boolean = { false },
) {
    val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    val problems = mutableListOf<String>()
    for (n in nodes) {
        val c = n.config
        val label = buildList {
            c.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            c.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
            c.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
            c.getOrNull(SemanticsActions.OnClick)?.label?.let { add(it) }
        }
        val spoken = label.joinToString(" ").trim()
        val where = "node ${n.id} '$spoken' ${n.size}"
        if (spoken.isEmpty()) problems += "no speakable label: $where"
        val w = n.size.width / density
        val h = n.size.height / density
        if ((w < minDp - 0.5f || h < minDp - 0.5f) && !allowSmall(spoken)) problems += "touch target ${w}x$h dp < $minDp: $where"
    }
    assertThat(nodes).isNotEmpty()
    assertThat(problems).isEmpty()
}
