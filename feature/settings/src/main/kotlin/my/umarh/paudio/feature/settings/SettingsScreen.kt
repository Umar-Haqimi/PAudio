package my.umarh.paudio.feature.settings

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
data object SettingsRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable<SettingsRoute> {
        SettingsScreen(onBack)
    }
}

@Composable
internal fun SettingsScreen(onBack: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_settings_title)) {
        Button(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
    }
}
