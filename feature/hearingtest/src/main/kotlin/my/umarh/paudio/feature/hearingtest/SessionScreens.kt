package my.umarh.paudio.feature.hearingtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import my.umarh.paudio.core.audiometry.RunKind
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.hear.FrequencyWaveProgress
import my.umarh.paudio.designsystem.hear.MorphingHearButton

@Composable
internal fun SessionScreen(
    session: SessionState,
    stimulusPlaying: Boolean,
    actions: HearingTestActions,
    onClose: () -> Unit,
) {
    when (session) {
        SessionState.Starting -> Status(stringResource(R.string.hearingtest_starting))
        is SessionState.ChannelCheck -> ChannelCheckScreen(session, actions)
        is SessionState.Calibrating -> CalibrationScreen(session, stimulusPlaying, actions)
        is SessionState.Testing -> when (session.mode) {
            TestMode.QUICK -> QuickScreen(session, actions)
            TestMode.THOROUGH -> ThoroughScreen(session, stimulusPlaying, actions)
        }
        is SessionState.Paused -> PausedScreen(session, actions)
        SessionState.Saving, is SessionState.Finished -> Status(stringResource(R.string.hearingtest_saving))
        is SessionState.Aborted -> AbortedScreen(session, actions, onClose)
        is SessionState.SaveFailed -> SaveFailedScreen(session, actions, onClose)
    }
}

@Composable
private fun Status(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

// ---- channel check (F3) ------------------------------------------------------------------------------------------

@Composable
internal fun ChannelCheckScreen(state: SessionState.ChannelCheck, actions: HearingTestActions) {
    Heading(stringResource(R.string.hearingtest_channel_heading))
    Text(
        stringResource(R.string.hearingtest_channel_step, state.step, state.steps),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(stringResource(R.string.hearingtest_channel_instruction), style = MaterialTheme.typography.bodyLarge)
    when (state.problem) {
        ChannelCheckProblem.WRONG_EAR -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.hearingtest_channel_wrong),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.hearingtest_channel_wrong_hint), style = MaterialTheme.typography.bodyMedium)
            }
        }
        ChannelCheckProblem.TOO_QUIET ->
            Text(stringResource(R.string.hearingtest_channel_too_quiet), style = MaterialTheme.typography.bodyMedium)
        ChannelCheckProblem.NONE -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { actions.answerChannel(ChannelAnswer.LEFT) },
            modifier = Modifier.weight(1f).heightIn(min = 72.dp).testTag(Tags.CHANNEL_LEFT),
        ) { Text(stringResource(R.string.hearingtest_channel_left), style = MaterialTheme.typography.titleMedium) }
        Button(
            onClick = { actions.answerChannel(ChannelAnswer.RIGHT) },
            modifier = Modifier.weight(1f).heightIn(min = 72.dp).testTag(Tags.CHANNEL_RIGHT),
        ) { Text(stringResource(R.string.hearingtest_channel_right), style = MaterialTheme.typography.titleMedium) }
    }
    OutlinedButton(
        onClick = { actions.answerChannel(ChannelAnswer.BOTH) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.CHANNEL_BOTH),
    ) { Text(stringResource(R.string.hearingtest_channel_both)) }
    TextButton(
        onClick = { actions.answerChannel(ChannelAnswer.LOUDER) },
        modifier = Modifier.heightIn(min = 48.dp).testTag(Tags.CHANNEL_LOUDER),
    ) { Text(stringResource(R.string.hearingtest_channel_louder)) }
}

// ---- calibration (F4) --------------------------------------------------------------------------------------------

@Composable
internal fun CalibrationScreen(state: SessionState.Calibrating, stimulusPlaying: Boolean, actions: HearingTestActions) {
    Heading(stringResource(R.string.hearingtest_cal_heading))
    when (state.phase) {
        CalibrationPhase.PREPARING -> {
            Text(
                if (state.rerun == 0) {
                    stringResource(R.string.hearingtest_cal_preparing)
                } else {
                    stringResource(R.string.hearingtest_cal_rerun, state.rerun)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            CircularProgressIndicator()
        }

        CalibrationPhase.RAMP -> {
            Text(stringResource(R.string.hearingtest_cal_instruction), style = MaterialTheme.typography.bodyLarge)
            if (state.rerun > 0) {
                Text(stringResource(R.string.hearingtest_cal_rerun, state.rerun), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.volumeRefused) {
                Text(stringResource(R.string.hearingtest_cal_refused_note), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MorphingHearButton(
                    onPress = actions::calibrationTap,
                    stimulusPlaying = stimulusPlaying,
                    enabled = state.canTap,
                    label = stringResource(R.string.hearingtest_cal_button),
                    modifier = Modifier.testTag(Tags.CAL_TAP),
                )
            }
            Text(stringResource(R.string.hearingtest_cal_dont_touch), style = MaterialTheme.typography.bodyMedium)
        }

        CalibrationPhase.MANUAL_VOLUME -> {
            Text(
                if (state.direction == VolumeDirection.UP) {
                    stringResource(R.string.hearingtest_cal_manual_up)
                } else {
                    stringResource(R.string.hearingtest_cal_manual_down)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(
                onClick = actions::manualVolumeDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.hearingtest_cal_manual_continue)) }
        }
    }
}

// ---- Quick (F5) --------------------------------------------------------------------------------------------------

/**
 * Quick shows NO timing cue (DECISIONS §27.1): the hear button and the wave stay visually steady (no pulse, no
 * enabled toggling, nothing locked to a trial or a stimulus), because a known listening window makes detection easier
 * and would let a user answer from the screen. The screen therefore takes no stimulus flag at all.
 */
@Composable
internal fun QuickScreen(state: SessionState.Testing, actions: HearingTestActions) {
    Heading(stringResource(R.string.hearingtest_quick_heading))
    Text(stringResource(R.string.hearingtest_quick_instruction), style = MaterialTheme.typography.bodyLarge)
    ProgressBlock(state, stimulusPlaying = false)
    // Always enabled: toggling `enabled` at stimulus onset would make TalkBack announce the change during the
    // stimulus (trap #20) and reveal the listening window. The session ignores taps between trials.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MorphingHearButton(
            onPress = { actions.respond(true) },
            stimulusPlaying = false,
            enabled = true,
            label = stringResource(R.string.hearingtest_quick_heard),
            modifier = Modifier.testTag(Tags.HEAR),
        )
        OutlinedButton(
            onClick = { actions.respond(false) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.DIDNT),
        ) { Text(stringResource(R.string.hearingtest_quick_didnt), style = MaterialTheme.typography.titleMedium) }
    }
}

/**
 * Wavy progress whose wavelength tracks the current frequency (F7). Deliberately NOT a live region: nothing here may
 * be announced while a stimulus plays (trap #20).
 */
@Composable
private fun ProgressBlock(state: SessionState.Testing, stimulusPlaying: Boolean) {
    val label = stringResource(R.string.hearingtest_progress_label)
    FrequencyWaveProgress(
        progress = state.progress,
        frequencyHz = state.frequencyHz,
        stimulusPlaying = stimulusPlaying,
        modifier = Modifier.semantics { contentDescription = label },
    )
    Text(
        pluralStringResource(R.plurals.hearingtest_points_progress, state.totalPoints, state.completedPoints, state.totalPoints),
        style = MaterialTheme.typography.labelLarge,
    )
    if (state.calibrationWarning) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PAudioIcon(PAudioIcons.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.hearingtest_cal_warning), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- Thorough (F6) -----------------------------------------------------------------------------------------------

@Composable
internal fun ThoroughScreen(state: SessionState.Testing, stimulusPlaying: Boolean, actions: HearingTestActions) {
    Heading(stringResource(R.string.hearingtest_thorough_heading))
    val dial = state.dial
    if (dial != null) {
        Text(
            if (dial.kind == RunKind.DESCENDING) {
                stringResource(R.string.hearingtest_thorough_desc_instruction)
            } else {
                stringResource(R.string.hearingtest_thorough_asc_instruction)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    ProgressBlock(state, stimulusPlaying)
    if (dial != null) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelDial(
                position = dial.position,
                minPosition = dial.minPosition,
                maxPosition = dial.maxPosition,
                stimulusPlaying = stimulusPlaying,
                onPositionChange = actions::dialTo,
                modifier = Modifier.height(320.dp).testTag(Tags.DIAL),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { actions.dialTo(dial.position + 1) },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.hearingtest_dial_louder)) }
                OutlinedButton(
                    onClick = { actions.dialTo(dial.position - 1) },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.hearingtest_dial_quieter)) }
            }
        }
        Button(
            onClick = actions::dialConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.DIAL_CONFIRM),
        ) {
            Text(
                if (dial.kind == RunKind.DESCENDING) {
                    stringResource(R.string.hearingtest_thorough_confirm_desc)
                } else {
                    stringResource(R.string.hearingtest_thorough_confirm_asc)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (dial.atTop) {
            OutlinedButton(onClick = actions::dialLimit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.hearingtest_thorough_limit_top))
            }
        }
        if (dial.atBottom) {
            OutlinedButton(onClick = actions::dialLimit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.hearingtest_thorough_limit_bottom))
            }
        }
    }
}

// ---- paused (F8) -------------------------------------------------------------------------------------------------

@Composable
internal fun PausedScreen(state: SessionState.Paused, actions: HearingTestActions) {
    Column(Modifier.testTag(Tags.PAUSED), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading(stringResource(R.string.hearingtest_paused_heading))
        val message = when (state.reason) {
            PauseReason.HEADSET_DISCONNECTED -> R.string.hearingtest_paused_headset
            PauseReason.VOLUME_CHANGED -> R.string.hearingtest_paused_volume
            PauseReason.FOCUS_LOST -> R.string.hearingtest_paused_focus
            PauseReason.BACKGROUNDED -> R.string.hearingtest_paused_background
            PauseReason.UNRELIABLE -> R.string.hearingtest_paused_unreliable
        }
        // Nothing is playing while paused, so a polite live region is safe here.
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.volumeKeysNeeded && state.reason == PauseReason.VOLUME_CHANGED) {
            Text(stringResource(R.string.hearingtest_paused_volume_keys), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.reason == PauseReason.UNRELIABLE) {
            Button(
                onClick = actions::redoTest,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.hearingtest_paused_redo)) }
            OutlinedButton(
                onClick = actions::keepGoing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    stringResource(
                        if (state.testFinished) R.string.hearingtest_paused_keep_results else R.string.hearingtest_paused_keep_going,
                    ),
                )
            }
        } else {
            Button(
                onClick = actions::resume,
                enabled = state.canResume,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.RESUME),
            ) { Text(stringResource(R.string.hearingtest_paused_continue)) }
        }
        TextButton(onClick = actions::abort, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hearingtest_paused_exit))
        }
    }
}

// ---- save failed ------------------------------------------------------------------------------------------------

@Composable
internal fun SaveFailedScreen(state: SessionState.SaveFailed, actions: HearingTestActions, onClose: () -> Unit) {
    Column(Modifier.testTag(Tags.SAVE_FAILED), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading(stringResource(R.string.hearingtest_save_failed_heading))
        // Nothing plays any more (the audio was closed before this state), so a polite live region is safe.
        Text(
            stringResource(
                if (state.profileMissing) R.string.hearingtest_save_failed_profile_missing else R.string.hearingtest_save_failed_text,
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.profileMissing) {
            Button(
                onClick = actions::saveToActiveProfile,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.SAVE_ACTIVE),
            ) { Text(stringResource(R.string.hearingtest_save_failed_use_active)) }
        } else {
            Button(
                onClick = actions::retrySave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(Tags.SAVE_RETRY),
            ) { Text(stringResource(R.string.hearingtest_save_failed_retry)) }
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hearingtest_save_failed_discard))
        }
    }
}

// ---- aborted ----------------------------------------------------------------------------------------------------

@Composable
internal fun AbortedScreen(state: SessionState.Aborted, actions: HearingTestActions, onClose: () -> Unit) {
    Column(Modifier.testTag(Tags.ABORTED), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading(stringResource(R.string.hearingtest_aborted_heading))
        val message = when (state.reason) {
            AbortReason.CANT_HEAR -> R.string.hearingtest_aborted_cant_hear
            AbortReason.VOLUME_RANGE -> R.string.hearingtest_aborted_volume_range
            AbortReason.AUDIO_ERROR -> R.string.hearingtest_aborted_audio_error
            AbortReason.PAUSE_TIMEOUT -> R.string.hearingtest_aborted_pause_timeout
            AbortReason.SAVE_FAILED -> R.string.hearingtest_aborted_save_failed
            AbortReason.NO_HEADPHONES -> R.string.hearingtest_aborted_no_headphones
            AbortReason.USER -> R.string.hearingtest_aborted_user
            AbortReason.PREVIOUS_SESSION_BUSY -> R.string.hearingtest_aborted_busy
        }
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.reason == AbortReason.CANT_HEAR) {
            Text(stringResource(R.string.hearingtest_aborted_cant_hear_hint), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = actions::tryAgain, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.hearingtest_aborted_try_again))
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.hearingtest_aborted_close))
        }
    }
}
