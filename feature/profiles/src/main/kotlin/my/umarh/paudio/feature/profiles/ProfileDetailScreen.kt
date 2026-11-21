package my.umarh.paudio.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.chart.HearingButterfly
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone

internal const val TAG_DELETE = "profile_delete"
internal const val TAG_EXPORT = "profile_export"
internal const val TAG_CONFIRM_DELETE = "profile_confirm_delete"

internal class DetailActions(
    val onBack: () -> Unit = {},
    val onRetest: () -> Unit = {},
    val onHealth: () -> Unit = {},
    val onPreferenceTest: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDuplicate: (String, SlotOption) -> Unit = { _, _ -> },
    val onMove: (SlotOption) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSeedColor: (Int) -> Unit = {},
    val onResetSeed: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onDismissRetest: () -> Unit = {},
)

@Composable
internal fun ProfileDetailScreen(
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onRetest: (Long) -> Unit,
    onHealth: (Long) -> Unit,
    onPreferenceTest: (Long) -> Unit,
    viewModel: ProfileDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = viewModel.exportBytes()
                val ok = bytes != null && withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                    } catch (_: Exception) {
                        false
                    }
                }
                viewModel.reportExport(ok)
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                DetailEvent.Deleted -> onBack()
                is DetailEvent.OpenProfile -> onOpenProfile(e.profileId)
            }
        }
    }
    val exportName = stringResource(R.string.profile_export_filename)
    ProfileDetailContent(
        state = state,
        actions = DetailActions(
            onBack = onBack,
            onRetest = { onRetest(state.profileId) },
            onHealth = { onHealth(state.profileId) },
            onPreferenceTest = { onPreferenceTest(state.profileId) },
            onExport = { exportLauncher.launch(exportName) },
            onRename = viewModel::rename,
            onDuplicate = viewModel::duplicate,
            onMove = viewModel::move,
            onDelete = viewModel::delete,
            onSeedColor = viewModel::setSeedColor,
            onResetSeed = viewModel::resetSeedColor,
            onDismissMessage = viewModel::dismissMessage,
            onDismissRetest = viewModel::dismissRetestOffer,
        ),
    )
}

private enum class Dialog { NONE, RENAME, DUPLICATE, MOVE, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileDetailContent(state: ProfileDetailUiState, actions: DetailActions) {
    var dialog by rememberSaveable { mutableStateOf(Dialog.NONE) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifBlank { stringResource(R.string.profile_detail_title) }) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                        PAudioIcon(PAudioIcons.ArrowBack, stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.profile_detail_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.message?.let { DetailMessageCard(it, actions.onDismissMessage) }
                SummaryCard(state)
                PrimaryActions(state, actions)
                HistoryCard(state)
                ColorCard(state, actions)
                ManageCard(
                    onRename = { dialog = Dialog.RENAME },
                    onDuplicate = { dialog = Dialog.DUPLICATE },
                    onMove = { dialog = Dialog.MOVE },
                    onExport = actions.onExport,
                    onDelete = { dialog = Dialog.DELETE },
                )
            }
        }
    }

    when (dialog) {
        Dialog.NONE -> Unit
        Dialog.RENAME -> TextInputDialog(
            title = stringResource(R.string.profile_dialog_rename),
            initial = state.name,
            confirmLabel = stringResource(R.string.profiles_save),
            onConfirm = {
                actions.onRename(it)
                dialog = Dialog.NONE
            },
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.DUPLICATE -> SlotDialog(
            title = stringResource(R.string.profile_dialog_duplicate),
            body = stringResource(R.string.profile_dialog_duplicate_body),
            slots = state.freeSlots,
            initialName = stringResource(R.string.profile_dialog_copy_suffix, state.name),
            confirmLabel = stringResource(R.string.profile_duplicate),
            askName = true,
            onConfirm = { name, slot ->
                actions.onDuplicate(name, slot)
                dialog = Dialog.NONE
            },
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.MOVE -> SlotDialog(
            title = stringResource(R.string.profile_dialog_move),
            body = stringResource(R.string.profile_dialog_move_body),
            slots = state.freeSlots,
            initialName = "",
            confirmLabel = stringResource(R.string.profile_move),
            askName = false,
            onConfirm = { _, slot ->
                actions.onMove(slot)
                dialog = Dialog.NONE
            },
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = Dialog.NONE },
            title = { Text(stringResource(R.string.profile_dialog_delete_title, state.name)) },
            text = { Text(stringResource(R.string.profile_dialog_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = Dialog.NONE
                        actions.onDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_CONFIRM_DELETE),
                ) { Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = Dialog.NONE }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profiles_cancel))
                }
            },
        )
    }
    if (state.offerRetest) {
        AlertDialog(
            onDismissRequest = actions.onDismissRetest,
            title = { Text(stringResource(R.string.profile_dialog_retest_title)) },
            text = { Text(stringResource(R.string.profile_dialog_retest_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDismissRetest()
                        actions.onRetest()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.profile_dialog_retest_now)) }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissRetest, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profile_dialog_retest_later))
                }
            },
        )
    }
}

@Composable
private fun DetailMessageCard(message: DetailMessage, onDismiss: () -> Unit) {
    val ok = message == DetailMessage.Exported
    val text = stringResource(
        when (message) {
            DetailMessage.SlotTaken -> R.string.profile_message_slot_taken
            DetailMessage.Failed -> R.string.profile_message_failed
            DetailMessage.Exported -> R.string.profile_message_exported
            DetailMessage.ExportFailed -> R.string.profile_message_export_failed
        },
    )
    SectionCard(modifier = Modifier.testTag(TAG_MESSAGE)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PAudioIcon(
                if (ok) PAudioIcons.Check else PAudioIcons.Warning,
                null,
                Modifier.size(24.dp),
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(text, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.profiles_message_dismiss))
        }
    }
}

@Composable
private fun SummaryCard(state: ProfileDetailUiState) {
    SectionCard {
        Text(state.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.profile_detail_listener, state.listenerName), style = MaterialTheme.typography.bodyMedium)
        val headset = when (val h = state.headset) {
            is HeadsetLabel.Named -> h.name
            HeadsetLabel.AnyHeadphones -> stringResource(R.string.profiles_any)
        }
        StatusChip(headset, StatusTone.Info, icon = PAudioIcons.Headphones)
        val b = state.butterfly
        when {
            b != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HearingButterfly(
                    leftHl = b.leftHl,
                    rightHl = b.rightHl,
                    frequenciesHz = b.freqsHz,
                    contentDescription = stringResource(R.string.profile_detail_butterfly_description),
                )
            }
            state.tests.isEmpty() -> Text(stringResource(R.string.profile_detail_no_test), style = MaterialTheme.typography.bodyMedium)
            else -> Text(stringResource(R.string.profile_detail_incomplete), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PrimaryActions(state: ProfileDetailUiState, actions: DetailActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onRetest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (state.tests.isEmpty()) R.string.profile_action_first_test else R.string.profile_action_retest))
        }
        FilledTonalButton(onClick = actions.onHealth, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.profile_action_health))
        }
        FilledTonalButton(onClick = actions.onPreferenceTest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.profile_action_preference))
        }
    }
}

@Composable
private fun HistoryCard(state: ProfileDetailUiState) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    SectionCard(title = stringResource(R.string.profile_history_title)) {
        Text(
            pluralStringResource(R.plurals.profile_history_count, state.tests.size, state.tests.size),
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.tests.isEmpty()) {
            Text(stringResource(R.string.profile_history_none), style = MaterialTheme.typography.bodyMedium)
        }
        state.tests.forEach { t ->
            val mode = stringResource(if (t.mode == TestMode.QUICK) R.string.profile_mode_quick else R.string.profile_mode_thorough)
            Column(Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(R.string.profile_history_row, format.format(Date(t.startedAt)), mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(if (t.reliable) R.string.profile_test_reliable else R.string.profile_test_unreliable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private class Swatch(val color: Color, val nameRes: Int)

private val Palette = listOf(
    Swatch(Color(0xFF3F6FB5), R.string.profile_color_blue),
    Swatch(Color(0xFF1B8A8A), R.string.profile_color_teal),
    Swatch(Color(0xFF3E8E41), R.string.profile_color_green),
    Swatch(Color(0xFFC59A00), R.string.profile_color_amber),
    Swatch(Color(0xFFD9722B), R.string.profile_color_orange),
    Swatch(Color(0xFFC0392B), R.string.profile_color_red),
    Swatch(Color(0xFFC2457F), R.string.profile_color_pink),
    Swatch(Color(0xFF7B52B8), R.string.profile_color_purple),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorCard(state: ProfileDetailUiState, actions: DetailActions) {
    SectionCard(title = stringResource(R.string.profile_color_title)) {
        Text(stringResource(R.string.profile_color_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Palette.forEach { s ->
                val selected = state.seedOverridden && state.seedColor == s.color.toArgb()
                val name = stringResource(s.nameRes)
                val selectedText = stringResource(R.string.profile_color_selected)
                Box(
                    Modifier
                        .size(48.dp)
                        .background(s.color, CircleShape)
                        .selectable(selected = selected, onClick = { actions.onSeedColor(s.color.toArgb()) }, role = Role.RadioButton)
                        .semantics {
                            contentDescription = name
                            if (selected) stateDescription = selectedText
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // A check mark, not the colour alone, marks the selection.
                    // Black on the light amber swatch: white would be only 2.6:1 (non-text contrast needs 3:1).
                    if (selected) {
                        PAudioIcon(PAudioIcons.Check, null, Modifier.size(24.dp), tint = if (s.color.luminance() > 0.3f) Color.Black else Color.White)
                    }
                }
            }
        }
        if (state.seedOverridden) {
            OutlinedButton(onClick = actions.onResetSeed, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profile_color_reset))
            }
        }
    }
}

@Composable
private fun ManageCard(
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.profile_manage_title)) {
        ManageRow(PAudioIcons.Edit, stringResource(R.string.profile_rename), onRename)
        ManageRow(PAudioIcons.ContentCopy, stringResource(R.string.profile_duplicate), onDuplicate)
        ManageRow(PAudioIcons.SwapHoriz, stringResource(R.string.profile_move), onMove)
        ManageRow(PAudioIcons.Upload, stringResource(R.string.profile_export), onExport, Modifier.testTag(TAG_EXPORT))
        ManageRow(
            PAudioIcons.Delete,
            stringResource(R.string.profile_delete),
            onDelete,
            Modifier.testTag(TAG_DELETE),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ManageRow(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PAudioIcon(icon, null, Modifier.size(24.dp), tint = tint)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SlotDialog(
    title: String,
    body: String,
    slots: List<SlotOption>,
    initialName: String,
    confirmLabel: String,
    askName: Boolean,
    onConfirm: (String, SlotOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by rememberSaveable { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (slots.isEmpty()) {
                    Text(stringResource(R.string.profile_dialog_no_slots))
                } else {
                    Text(body)
                    if (askName) {
                        androidx.compose.material3.OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(ProfilesViewModel.MAX_NAME) },
                            label = { Text(stringResource(R.string.profiles_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.selectableGroup()) {
                        slots.forEachIndexed { i, slot ->
                            val label = when (val l = slot.label) {
                                is HeadsetLabel.Named -> l.name
                                HeadsetLabel.AnyHeadphones -> stringResource(R.string.profiles_any)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = i == selected, onClick = { selected = i }, role = Role.RadioButton),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = i == selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                                Text(label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { slots.getOrNull(selected)?.let { onConfirm(name, it) } },
                enabled = slots.isNotEmpty() && (!askName || name.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}
