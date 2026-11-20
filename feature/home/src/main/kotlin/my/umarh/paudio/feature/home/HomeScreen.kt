package my.umarh.paudio.feature.home

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
data object HomeRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.homeScreen(onHearingTest: () -> Unit, onPreferenceTest: () -> Unit, onHealth: () -> Unit, onProfiles: () -> Unit, onSettings: () -> Unit) {
    composable<HomeRoute> {
        HomeScreen(onHearingTest, onPreferenceTest, onHealth, onProfiles, onSettings)
    }
}

@Composable
internal fun HomeScreen(onHearingTest: () -> Unit, onPreferenceTest: () -> Unit, onHealth: () -> Unit, onProfiles: () -> Unit, onSettings: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_home_title)) {
        Button(onClick = onHearingTest) { Text(stringResource(R.string.home_action_hearing_test)) }
        Button(onClick = onPreferenceTest) { Text(stringResource(R.string.home_action_preference_test)) }
        Button(onClick = onHealth) { Text(stringResource(R.string.home_action_health)) }
        Button(onClick = onProfiles) { Text(stringResource(R.string.home_action_profiles)) }
        Button(onClick = onSettings) { Text(stringResource(R.string.home_action_settings)) }
    }
}
