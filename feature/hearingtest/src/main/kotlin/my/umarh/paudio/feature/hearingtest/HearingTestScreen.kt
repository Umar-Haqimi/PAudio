package my.umarh.paudio.feature.hearingtest

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import my.umarh.paudio.designsystem.PlaceholderScreen
import androidx.compose.ui.res.stringResource

/** Type-safe navigation destination for this feature. */
@Serializable
data object HearingTestRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.hearingTestScreen(onShowResults: () -> Unit, onBack: () -> Unit) {
    composable<HearingTestRoute> {
        HearingTestScreen(onShowResults, onBack)
    }
}

@Composable
internal fun HearingTestScreen(onShowResults: () -> Unit, onBack: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_hearingtest_title)) {
        Button(onClick = onShowResults) { Text(stringResource(R.string.hearingtest_show_results)) }
        Button(onClick = onBack) { Text(stringResource(R.string.hearingtest_back)) }
    }
}
