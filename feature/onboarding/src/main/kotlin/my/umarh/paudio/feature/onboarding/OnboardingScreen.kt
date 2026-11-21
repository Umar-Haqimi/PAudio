package my.umarh.paudio.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.component.DisclaimerCard
import my.umarh.paudio.designsystem.component.SectionCard

/** Type-safe navigation destination for this feature. */
@Serializable
data object OnboardingRoute

/**
 * First-run onboarding (F1). [onFinished] is called once the listener exists, the service was started and
 * onboarding is marked done.
 */
fun NavGraphBuilder.onboardingScreen(onFinished: () -> Unit) {
    composable<OnboardingRoute> {
        OnboardingScreen(onFinished)
    }
}

internal const val TAG_ACK = "onboarding_ack"
internal const val TAG_CONTINUE = "onboarding_continue"
internal const val TAG_FINISH = "onboarding_finish"

@Composable
internal fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionNeeded = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationResult(granted)
    }
    val defaultName = stringResource(R.string.onboarding_listener_default)
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    OnboardingContent(
        state = state,
        permissionNeeded = permissionNeeded,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onAcknowledge = viewModel::acknowledgeDisclaimer,
        onRequestPermission = {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onNameChange = viewModel::setListenerName,
        onFinish = { viewModel.finish(defaultName) },
    )
}

@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    permissionNeeded: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onAcknowledge: () -> Unit,
    onRequestPermission: () -> Unit,
    onNameChange: (String) -> Unit,
    onFinish: () -> Unit,
) {
    // Nothing to ask on API < 33 or when it is already granted.
    LaunchedEffect(state.step, permissionNeeded) {
        if (state.step == OnboardingStep.NOTIFICATIONS && !permissionNeeded) onNext()
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step_counter, state.step.ordinal + 1, OnboardingStep.entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> Welcome()
                    OnboardingStep.HOW_IT_WORKS -> HowItWorks()
                    OnboardingStep.DISCLAIMER -> Disclaimer()
                    OnboardingStep.NOTIFICATIONS -> Notifications(state.notificationsDenied)
                    OnboardingStep.LISTENER -> Listener(state, onNameChange)
                }
            }
            Actions(state, onNext, onBack, onAcknowledge, onRequestPermission, onFinish)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(24.dp))
    PAudioIcon(PAudioIcons.Hearing, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
    Text(
        stringResource(R.string.onboarding_welcome_headline),
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun HowItWorks() {
    Heading(stringResource(R.string.onboarding_how_title))
    SectionCard(title = stringResource(R.string.onboarding_how_1_title)) { Text(stringResource(R.string.onboarding_how_1_body)) }
    SectionCard(title = stringResource(R.string.onboarding_how_2_title)) { Text(stringResource(R.string.onboarding_how_2_body)) }
    SectionCard(title = stringResource(R.string.onboarding_how_3_title)) { Text(stringResource(R.string.onboarding_how_3_body)) }
}

@Composable
private fun Disclaimer() {
    Heading(stringResource(R.string.onboarding_disclaimer_title))
    DisclaimerCard(stringResource(R.string.onboarding_disclaimer_text))
}

@Composable
private fun Notifications(denied: Boolean) {
    Heading(stringResource(R.string.onboarding_notifications_title))
    Text(stringResource(R.string.onboarding_notifications_body), style = MaterialTheme.typography.bodyLarge)
    if (denied) {
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                PAudioIcon(PAudioIcons.Warning, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.onboarding_notifications_denied))
            }
        }
    }
}

@Composable
private fun Listener(state: OnboardingUiState, onNameChange: (String) -> Unit) {
    Heading(stringResource(R.string.onboarding_listener_title))
    Text(stringResource(R.string.onboarding_listener_body), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = state.listenerName,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.onboarding_listener_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_listener_default)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.error) {
        Text(stringResource(R.string.onboarding_error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Actions(
    state: OnboardingUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onAcknowledge: () -> Unit,
    onRequestPermission: () -> Unit,
    onFinish: () -> Unit,
) {
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.step) {
            OnboardingStep.WELCOME -> Button(onClick = onNext, modifier = buttonModifier.testTag(TAG_CONTINUE)) {
                Text(stringResource(R.string.onboarding_get_started))
            }
            OnboardingStep.HOW_IT_WORKS -> Button(onClick = onNext, modifier = buttonModifier.testTag(TAG_CONTINUE)) {
                Text(stringResource(R.string.onboarding_continue))
            }
            // The only way forward from the disclaimer is the explicit acknowledgement.
            OnboardingStep.DISCLAIMER -> Button(onClick = onAcknowledge, modifier = buttonModifier.testTag(TAG_ACK)) {
                Text(stringResource(R.string.onboarding_disclaimer_ack))
            }
            OnboardingStep.NOTIFICATIONS ->
                if (state.notificationsDenied) {
                    Button(onClick = onNext, modifier = buttonModifier.testTag(TAG_CONTINUE)) {
                        Text(stringResource(R.string.onboarding_continue))
                    }
                } else {
                    Button(onClick = onRequestPermission, modifier = buttonModifier.testTag(TAG_CONTINUE)) {
                        Text(stringResource(R.string.onboarding_notifications_allow))
                    }
                }
            OnboardingStep.LISTENER -> Button(
                onClick = onFinish,
                enabled = !state.saving,
                modifier = buttonModifier.testTag(TAG_FINISH),
            ) {
                Text(stringResource(if (state.saving) R.string.onboarding_saving else R.string.onboarding_finish))
            }
        }
        if (state.step != OnboardingStep.WELCOME) {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_back))
            }
        }
    }
}
