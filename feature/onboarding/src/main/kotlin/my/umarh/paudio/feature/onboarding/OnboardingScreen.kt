package my.umarh.paudio.feature.onboarding

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
data object OnboardingRoute

/** Registers the placeholder destination; replaced by the real flow in a later milestone. */
fun NavGraphBuilder.onboardingScreen(onFinished: () -> Unit) {
    composable<OnboardingRoute> {
        OnboardingScreen(onFinished)
    }
}

@Composable
internal fun OnboardingScreen(onFinished: () -> Unit) {
    PlaceholderScreen(title = stringResource(R.string.feature_onboarding_title)) {
        Button(onClick = onFinished) { Text(stringResource(R.string.onboarding_continue)) }
    }
}
