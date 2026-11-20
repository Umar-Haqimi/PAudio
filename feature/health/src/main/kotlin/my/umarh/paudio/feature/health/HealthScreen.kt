package my.umarh.paudio.feature.health

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
data object HealthRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.healthScreen(onBack: () -> Unit) {
    composable<HealthRoute> {
        HealthScreen(onBack)
    }
}

@Composable
internal fun HealthScreen(onBack: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_health_title)) {
        Button(onClick = onBack) { Text(stringResource(R.string.health_back)) }
    }
}
