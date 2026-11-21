package my.umarh.paudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.umarh.paudio.designsystem.PAudioTheme
import my.umarh.paudio.feature.home.HomeRoute
import my.umarh.paudio.feature.onboarding.OnboardingRoute
import my.umarh.paudio.navigation.PAudioNavHost

/** The whole app UI: profile-seeded theme (F25), navigation, and the app-level "Which headphones are these?" dialog. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val start by viewModel.start.collectAsStateWithLifecycle()
    val seed by viewModel.seedColor.collectAsStateWithLifecycle()
    val launchRoute by viewModel.launchRoute.collectAsStateWithLifecycle()
    val question by viewModel.whichHeadphones.collectAsStateWithLifecycle()
    PAudioTheme(seedColor = seed) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (start) {
                AppStart.Loading -> Unit
                AppStart.Onboarding, AppStart.Home -> PAudioNavHost(
                    startDestination = if (start == AppStart.Home) HomeRoute else OnboardingRoute,
                    launchRoute = launchRoute,
                    onLaunchRouteHandled = viewModel::consumeLaunchRoute,
                )
            }
        }
        question?.let { q ->
            WhichHeadphonesDialog(q, onPick = viewModel::answerWhichHeadphones)
        }
    }
}

/** SPEC 5.5: two profiles share one wired key; ask which one applies until the headphones are unplugged. */
@Composable
internal fun WhichHeadphonesDialog(question: WhichHeadphonesUi, onPick: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text(stringResource(R.string.which_headphones_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.which_headphones_body))
                question.candidates.forEach { c ->
                    TextButton(
                        onClick = { onPick(c.profileId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.which_headphones_candidate, c.profileName, c.listenerName))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onPick(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.which_headphones_not_now))
            }
        },
    )
}
