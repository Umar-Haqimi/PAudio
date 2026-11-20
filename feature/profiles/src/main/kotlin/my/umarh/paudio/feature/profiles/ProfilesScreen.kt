package my.umarh.paudio.feature.profiles

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
data object ProfilesRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.profilesScreen(onBack: () -> Unit) {
    composable<ProfilesRoute> {
        ProfilesScreen(onBack)
    }
}

@Composable
internal fun ProfilesScreen(onBack: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_profiles_title)) {
        Button(onClick = onBack) { Text(stringResource(R.string.profiles_back)) }
    }
}
