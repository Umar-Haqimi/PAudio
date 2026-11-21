package my.umarh.paudio.feature.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import java.text.NumberFormat
import kotlinx.serialization.Serializable
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.chart.HearingButterfly
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.SegmentedToggle
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone
import my.umarh.paudio.service.EngineStatus

/** Type-safe navigation destination for this feature. */
@Serializable
data object HomeRoute

/**
 * Home (SPEC section 4 item 2).
 * @param onHealth receives the profile shown on the card (null when there is none).
 * @param onCreateProfile "Create profile for this headset": opens the Profiles create flow.
 */
fun NavGraphBuilder.homeScreen(
    onHearingTest: () -> Unit,
    onPreferenceTest: () -> Unit,
    onHealth: (profileId: Long?) -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onCreateProfile: () -> Unit,
) {
    composable<HomeRoute> {
        HomeScreen(
            HomeActions(
                onHearingTest = onHearingTest,
                onPreferenceTest = onPreferenceTest,
                onHealth = onHealth,
                onProfiles = onProfiles,
                onSettings = onSettings,
                onCreateProfile = onCreateProfile,
            ),
        )
    }
}

internal class HomeActions(
    val onHearingTest: () -> Unit = {},
    val onPreferenceTest: () -> Unit = {},
    val onHealth: (Long?) -> Unit = {},
    val onProfiles: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onCreateProfile: () -> Unit = {},
    val onTogglePersonalized: (Boolean) -> Unit = {},
    val onStrengthChanged: (Float) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTurnOn: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
)

internal const val TAG_EMPTY_CTA = "home_empty_cta"
internal const val TAG_TURN_ON = "home_turn_on"
internal const val TAG_STATUS = "home_status"

@Composable
internal fun HomeScreen(actions: HomeActions, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationPermission() }
    HomeContent(
        state = state,
        actions = HomeActions(
            onHearingTest = actions.onHearingTest,
            onPreferenceTest = actions.onPreferenceTest,
            onHealth = actions.onHealth,
            onProfiles = actions.onProfiles,
            onSettings = actions.onSettings,
            onCreateProfile = actions.onCreateProfile,
            onTogglePersonalized = viewModel::setPersonalized,
            onStrengthChanged = viewModel::setStrength,
            onRetry = viewModel::retry,
            onTurnOn = viewModel::turnOn,
            onOpenNotificationSettings = { openNotificationSettings(context) },
        ),
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No settings activity: the banner copy still explains the situation.
    }
}

@Composable
internal fun HomeContent(state: HomeUiState, actions: HomeActions) {
    Scaffold { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.feature_home_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            if (state.notificationsBlocked) NotificationsBanner(actions.onOpenNotificationSettings)
            if (state.isEmpty) {
                EmptyCta(actions.onHearingTest)
            }
            if (!state.running) {
                OffCard(actions.onTurnOn)
            } else {
                StatusSection(state, actions)
            }
            if (!state.isEmpty && state.profile != null) {
                ProfileCard(state.profile)
                SoundCard(state, actions)
            }
            ActionsSection(state, actions)
        }
    }
}

@Composable
private fun NotificationsBanner(onOpenSettings: () -> Unit) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PAudioIcon(PAudioIcons.Notifications, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.home_notifications_blocked))
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.home_notifications_open_settings))
        }
    }
}

@Composable
private fun EmptyCta(onHearingTest: () -> Unit) {
    SectionCard {
        PAudioIcon(PAudioIcons.Hearing, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = onHearingTest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(TAG_EMPTY_CTA),
        ) {
            Text(stringResource(R.string.home_empty_cta), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun OffCard(onTurnOn: () -> Unit) {
    SectionCard(title = stringResource(R.string.home_off_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PAudioIcon(PAudioIcons.PowerSettingsNew, null, Modifier.size(24.dp))
            Text(stringResource(R.string.home_off_body))
        }
        Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_TURN_ON)) {
            Text(stringResource(R.string.home_off_turn_on))
        }
    }
}

private fun toneOf(state: EngineState): StatusTone = when (state) {
    is EngineState.Active -> StatusTone.Ok
    is EngineState.Legacy -> if (state.active) StatusTone.Ok else StatusTone.Info
    EngineState.Conflict -> StatusTone.Warn
    is EngineState.Error -> StatusTone.Error
    else -> StatusTone.Info
}

private fun iconOf(state: EngineState): Int = when (state) {
    is EngineState.Active -> PAudioIcons.Check
    is EngineState.Legacy -> PAudioIcons.GraphicEq
    EngineState.Conflict -> PAudioIcons.Warning
    is EngineState.Error -> PAudioIcons.Error
    EngineState.SpeakerOutput -> PAudioIcons.Speaker
    is EngineState.NoProfile -> PAudioIcons.Headphones
    EngineState.Bypassed -> PAudioIcons.Pause
    EngineState.Off, EngineState.Starting, EngineState.Waiting -> PAudioIcons.GraphicEq
}

private fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

@Composable
private fun StatusSection(state: HomeUiState, actions: HomeActions) {
    val context = LocalContext.current
    val text = if (state.fault) {
        stringResource(R.string.home_status_fault)
    } else {
        EngineStatus.textOf(state.engineState) { appLabel(context, it) }.format(context)
    }
    val hint = if (state.fault) null else EngineStatus.hintOf(state.engineState, state.noSessionHint)?.format(context)
    val retryable = !state.fault && (state.engineState == EngineState.Conflict || state.engineState is EngineState.Error)
    SectionCard(title = stringResource(R.string.home_status_title)) {
        val chipModifier = if (retryable) {
            Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.home_status_retry_action),
                    role = Role.Button,
                    onClick = actions.onRetry,
                )
        } else {
            Modifier
        }
        Box(chipModifier.testTag(TAG_STATUS), contentAlignment = Alignment.CenterStart) {
            StatusChip(
                text = text,
                tone = if (state.fault) StatusTone.Warn else toneOf(state.engineState),
                icon = if (state.fault) PAudioIcons.SyncProblem else iconOf(state.engineState),
            )
        }
        if (retryable) {
            Text(
                stringResource(R.string.home_status_tap_to_retry),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        if (state.noProfileForHeadset) {
            Button(onClick = actions.onCreateProfile, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_no_profile_action))
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: HomeProfile) {
    SectionCard(title = stringResource(R.string.home_profile_card_title)) {
        val b = profile.butterfly
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (b != null) {
                HearingButterfly(
                    leftHl = b.leftHl,
                    rightHl = b.rightHl,
                    frequenciesHz = b.freqsHz,
                    mini = true,
                    contentDescription = stringResource(R.string.home_butterfly_description, profile.profileName),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.profileName, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.home_profile_listener, profile.listenerName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val headset = when (val h = profile.headset) {
                    is HeadsetLabel.Named -> h.name
                    HeadsetLabel.AnyHeadphones -> stringResource(R.string.home_headset_any)
                }
                StatusChip(text = headset, tone = StatusTone.Info, icon = PAudioIcons.Headphones)
            }
        }
        if (b == null) {
            Text(stringResource(R.string.home_butterfly_missing), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SoundCard(state: HomeUiState, actions: HomeActions) {
    val profile = state.profile ?: return
    SectionCard(title = stringResource(R.string.home_personalization_title)) {
        SegmentedToggle(
            personalized = state.personalized,
            onPersonalizedChange = actions.onTogglePersonalized,
            enabled = state.engineFollowsProfile,
        )
        if (!state.engineFollowsProfile) {
            Text(
                stringResource(R.string.home_personalization_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StrengthSlider(profile, actions.onStrengthChanged)
    }
}

@Composable
private fun StrengthSlider(profile: HomeProfile, onChanged: (Float) -> Unit) {
    var value by remember(profile.profileId) { mutableFloatStateOf(profile.strength) }
    var dragging by remember { mutableStateOf(false) }
    // Follow stored changes (another screen, import) unless the user is dragging.
    LaunchedEffect(profile.strength) { if (!dragging) value = profile.strength }
    val percent = NumberFormat.getPercentInstance().format(value.toDouble())
    val description = stringResource(R.string.home_strength_description)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.home_strength_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.home_strength_value, percent), style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                dragging = false
                onChanged(value)
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = percent
                },
        )
    }
}

@Composable
private fun ActionsSection(state: HomeUiState, actions: HomeActions) {
    SectionCard(title = stringResource(R.string.home_actions_title)) {
        if (!state.isEmpty) {
            ActionRow(PAudioIcons.Hearing, stringResource(R.string.home_action_hearing_test), actions.onHearingTest)
        }
        ActionRow(PAudioIcons.Tune, stringResource(R.string.home_action_preference_test), actions.onPreferenceTest)
        ActionRow(
            PAudioIcons.HealthAndSafety,
            stringResource(R.string.home_action_health),
        ) { actions.onHealth(state.profile?.profileId) }
        ActionRow(PAudioIcons.Group, stringResource(R.string.home_action_profiles), actions.onProfiles)
        ActionRow(PAudioIcons.Settings, stringResource(R.string.home_action_settings), actions.onSettings)
    }
}

@Composable
private fun ActionRow(icon: Int, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PAudioIcon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            PAudioIcon(PAudioIcons.ChevronRight, null, Modifier.size(24.dp))
        }
    }
}
