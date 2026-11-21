package my.umarh.paudio.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import java.text.NumberFormat
import kotlinx.serialization.Serializable
import my.umarh.paudio.data.settings.EngineMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.component.SectionCard

/** Type-safe navigation destinations for this feature. */
@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

/** Settings (F20, F23) and About (F24). [onAbout] opens the About screen registered by [aboutScreen]. */
fun NavGraphBuilder.settingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    composable<SettingsRoute> {
        SettingsScreen(onBack = onBack, onAbout = onAbout)
    }
}

internal const val TAG_MODE_SESSION = "settings_mode_session"
internal const val TAG_MODE_LEGACY = "settings_mode_legacy"
internal const val TAG_BOOT = "settings_boot"
internal const val TAG_SERVICE = "settings_service"

internal class SettingsActions(
    val onBack: () -> Unit = {},
    val onAbout: () -> Unit = {},
    val onMode: (EngineMode) -> Unit = {},
    val onStartOnBoot: (Boolean) -> Unit = {},
    val onDefaultStrength: (Float) -> Unit = {},
    val onServiceOn: (Boolean) -> Unit = {},
)

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state,
        SettingsActions(
            onBack = onBack,
            onAbout = onAbout,
            onMode = viewModel::setMode,
            onStartOnBoot = viewModel::setStartOnBoot,
            onDefaultStrength = viewModel::setDefaultStrength,
            onServiceOn = viewModel::setServiceOn,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(state: SettingsUiState, actions: SettingsActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                        PAudioIcon(PAudioIcons.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServiceCard(state, actions)
            ModeCard(state, actions)
            BootCard(state, actions)
            StrengthCard(state, actions)
            AboutRow(actions.onAbout)
        }
    }
}

@Composable
private fun ServiceCard(state: SettingsUiState, actions: SettingsActions) {
    SectionCard(title = stringResource(R.string.settings_service_title)) {
        SwitchRow(
            label = stringResource(R.string.settings_service_switch),
            checked = state.serviceRunning,
            onCheckedChange = actions.onServiceOn,
            modifier = Modifier.testTag(TAG_SERVICE),
        )
        Text(
            stringResource(if (state.serviceRunning) R.string.settings_service_on_body else R.string.settings_service_off_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModeCard(state: SettingsUiState, actions: SettingsActions) {
    SectionCard(title = stringResource(R.string.settings_mode_title)) {
        Text(stringResource(R.string.settings_mode_intro), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeRow(
                title = stringResource(R.string.settings_mode_session),
                body = stringResource(R.string.settings_mode_session_body),
                selected = state.mode == EngineMode.SESSION,
                onSelect = { actions.onMode(EngineMode.SESSION) },
                modifier = Modifier.testTag(TAG_MODE_SESSION),
            )
            ModeRow(
                title = stringResource(R.string.settings_mode_legacy),
                body = stringResource(R.string.settings_mode_legacy_body),
                selected = state.mode == EngineMode.LEGACY,
                onSelect = { actions.onMode(EngineMode.LEGACY) },
                modifier = Modifier.testTag(TAG_MODE_LEGACY),
            )
        }
    }
}

@Composable
private fun ModeRow(title: String, body: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            // The row carries the selection semantics; the button itself is decorative.
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BootCard(state: SettingsUiState, actions: SettingsActions) {
    SectionCard(title = stringResource(R.string.settings_boot_title)) {
        SwitchRow(
            label = stringResource(R.string.settings_boot_switch),
            checked = state.startOnBoot,
            onCheckedChange = actions.onStartOnBoot,
            modifier = Modifier.testTag(TAG_BOOT),
        )
        Text(stringResource(R.string.settings_boot_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun StrengthCard(state: SettingsUiState, actions: SettingsActions) {
    var value by remember { mutableFloatStateOf(state.defaultStrength) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.defaultStrength) { if (!dragging) value = state.defaultStrength }
    val percent = NumberFormat.getPercentInstance().format(value.toDouble())
    val description = stringResource(R.string.settings_strength_description)
    SectionCard(title = stringResource(R.string.settings_strength_title)) {
        Text(stringResource(R.string.settings_strength_body), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(stringResource(R.string.settings_strength_value, percent), style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                dragging = false
                actions.onDefaultStrength(value)
            },
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = description
                stateDescription = percent
            },
        )
    }
}

@Composable
private fun AboutRow(onAbout: () -> Unit) {
    Surface(
        onClick = onAbout,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PAudioIcon(PAudioIcons.Info, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.settings_about_row),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            PAudioIcon(PAudioIcons.ChevronRight, null, Modifier.size(24.dp))
        }
    }
}
