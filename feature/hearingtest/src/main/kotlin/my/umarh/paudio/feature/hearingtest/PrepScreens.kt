package my.umarh.paudio.feature.hearingtest

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import my.umarh.paudio.audio.device.NoiseLevel
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.component.InstructionRow
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone

/** Quick ≈ 6–8 min (DECISIONS §18: the spec's 4–5 min is unreachable); Thorough ≈ 7–9 min. */
internal const val QUICK_MIN_MINUTES = 6
internal const val QUICK_MAX_MINUTES = 8
internal const val THOROUGH_MIN_MINUTES = 7
internal const val THOROUGH_MAX_MINUTES = 9

@Composable
internal fun ModeChoiceScreen(actions: HearingTestActions) {
    Text(
        stringResource(R.string.hearingtest_mode_heading),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.hearingtest_mode_intro), style = MaterialTheme.typography.bodyLarge)
    ModeCard(
        title = stringResource(R.string.hearingtest_mode_quick_title),
        duration = stringResource(R.string.hearingtest_mode_quick_duration, QUICK_MIN_MINUTES, QUICK_MAX_MINUTES),
        description = stringResource(R.string.hearingtest_mode_quick_desc),
        tag = Tags.MODE_QUICK,
        onClick = { actions.chooseMode(TestMode.QUICK) },
    )
    ModeCard(
        title = stringResource(R.string.hearingtest_mode_thorough_title),
        duration = stringResource(R.string.hearingtest_mode_thorough_duration, THOROUGH_MIN_MINUTES, THOROUGH_MAX_MINUTES),
        description = stringResource(R.string.hearingtest_mode_thorough_desc),
        tag = Tags.MODE_THOROUGH,
        onClick = { actions.chooseMode(TestMode.THOROUGH) },
    )
}

@Composable
private fun ModeCard(title: String, duration: String, description: String, tag: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(duration, style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun PrepScreen(state: HearingTestUiState.Prep, noiseLevel: StateFlow<NoiseLevel?>, actions: HearingTestActions) {
    Column(Modifier.testTag(Tags.PREP), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.hearingtest_prep_heading),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        SectionCard {
            InstructionRow(stringResource(R.string.hearingtest_prep_quiet), PAudioIcons.VolumeOff)
            InstructionRow(stringResource(R.string.hearingtest_prep_anc), PAudioIcons.GraphicEqOff)
            InstructionRow(stringResource(R.string.hearingtest_prep_dnd), PAudioIcons.DoNotDisturbOnTotalSilence)
            DndStatusChip(state.dnd)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = state.ancOn, role = Role.Checkbox, onValueChange = actions::setAncOn)
                .testTag(Tags.ANC),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = state.ancOn, onCheckedChange = null)
            Text(stringResource(R.string.hearingtest_prep_anc_checkbox), style = MaterialTheme.typography.bodyLarge)
        }

        if (state.noise != NoiseMeterState.DENIED) NoiseCard(state.noise, noiseLevel, actions)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PAudioIcon(PAudioIcons.Info, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.hearingtest_prep_eq_tip), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.someChecksUnknown) {
            Text(stringResource(R.string.hearingtest_prep_unknown_checks), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.blockers.isNotEmpty()) Blockers(state.blockers)

        Button(
            onClick = actions::startTest,
            enabled = state.canStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(Tags.START),
        ) { Text(stringResource(R.string.hearingtest_prep_start)) }
    }
}

@Composable
private fun DndStatusChip(dnd: DndStatus) {
    when (dnd) {
        DndStatus.ON -> StatusChip(stringResource(R.string.hearingtest_prep_dnd_on), StatusTone.Ok, icon = PAudioIcons.Check)
        DndStatus.OFF -> StatusChip(stringResource(R.string.hearingtest_prep_dnd_off), StatusTone.Warn, icon = PAudioIcons.Warning)
        DndStatus.UNKNOWN -> StatusChip(stringResource(R.string.hearingtest_prep_dnd_unknown), StatusTone.Info)
    }
}

@Composable
private fun Blockers(blockers: List<PrepBlocker>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Tags.BLOCKERS),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.hearingtest_prep_blockers_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            for (b in blockers) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PAudioIcon(PAudioIcons.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(blockerText(b), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun blockerText(b: PrepBlocker): String = when (b) {
    PrepBlocker.Mono -> stringResource(R.string.hearingtest_blocker_mono)
    PrepBlocker.Balance -> stringResource(R.string.hearingtest_blocker_balance)
    PrepBlocker.Spatializer -> stringResource(R.string.hearingtest_blocker_spatializer)
    PrepBlocker.NoHeadphones -> stringResource(R.string.hearingtest_blocker_no_headphones)
    is PrepBlocker.WrongHeadset -> stringResource(R.string.hearingtest_blocker_wrong_headset, b.displayName)
    PrepBlocker.ProfileMissing -> stringResource(R.string.hearingtest_blocker_profile_missing)
}

/**
 * Optional room-noise meter. The microphone permission is requested only when the user taps the button; if it is
 * denied the card disappears. The level flow is collected ONLY inside [NoiseLevelRow], so the AudioRecord stops the
 * moment this card (or the whole page) leaves composition.
 */
@Composable
private fun NoiseCard(state: NoiseMeterState, noiseLevel: StateFlow<NoiseLevel?>, actions: HearingTestActions) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        actions.onNoisePermissionResult(granted)
    }
    SectionCard(title = stringResource(R.string.hearingtest_prep_noise_heading)) {
        Text(stringResource(R.string.hearingtest_prep_noise_explain), style = MaterialTheme.typography.bodyMedium)
        when (state) {
            NoiseMeterState.OFFERED -> OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) actions.onNoisePermissionResult(true) else launcher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(Tags.NOISE_OFFER),
            ) { Text(stringResource(R.string.hearingtest_prep_noise_offer)) }

            NoiseMeterState.ENABLED -> {
                NoiseLevelRow(noiseLevel)
                TextButton(onClick = actions::disableNoiseMeter, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.hearingtest_prep_noise_stop))
                }
            }

            NoiseMeterState.DENIED -> Unit
        }
    }
}

@Composable
private fun NoiseLevelRow(noiseLevel: StateFlow<NoiseLevel?>) {
    val level by noiseLevel.collectAsStateWithLifecycle()
    val (text, tone) = when (level) {
        null -> stringResource(R.string.hearingtest_prep_noise_measuring) to StatusTone.Info
        NoiseLevel.QUIET -> stringResource(R.string.hearingtest_prep_noise_quiet) to StatusTone.Ok
        NoiseLevel.OK -> stringResource(R.string.hearingtest_prep_noise_ok) to StatusTone.Ok
        NoiseLevel.TOO_NOISY -> stringResource(R.string.hearingtest_prep_noise_too_noisy) to StatusTone.Warn
        NoiseLevel.UNKNOWN -> stringResource(R.string.hearingtest_prep_noise_unknown) to StatusTone.Info
    }
    StatusChip(text, tone, modifier = Modifier.testTag(Tags.NOISE_LEVEL))
}
