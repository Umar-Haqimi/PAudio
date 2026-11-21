package my.umarh.paudio.feature.profiles

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportErrorReason
import my.umarh.paudio.data.transfer.ProfileImportValidator
import my.umarh.paudio.designsystem.PAudioIcon
import my.umarh.paudio.designsystem.PAudioIcons
import my.umarh.paudio.designsystem.component.SectionCard
import my.umarh.paudio.designsystem.component.StatusChip
import my.umarh.paudio.designsystem.component.StatusTone

/**
 * Profiles list. [createForCurrent] opens the "create profile for the headset connected now" dialog on arrival
 * (Home's "Create profile for this headset", the unknown-headset notification).
 */
@Serializable
data class ProfilesRoute(val createForCurrent: Boolean = false)

@Serializable
data class ProfileDetailRoute(val profileId: Long)

/**
 * Registers the profile list and the profile detail destinations (F16-F18).
 * @param onOpenProfile navigate to `ProfileDetailRoute(id)` (the app owns the NavController).
 * @param onRetest / [onHealth] / [onPreferenceTest] receive the profile id.
 */
fun NavGraphBuilder.profilesScreen(
    onBack: () -> Unit,
    onOpenProfile: (profileId: Long) -> Unit,
    onRetest: (profileId: Long) -> Unit,
    onHealth: (profileId: Long) -> Unit,
    onPreferenceTest: (profileId: Long) -> Unit,
) {
    composable<ProfilesRoute> {
        ProfilesScreen(onBack = onBack, onOpenProfile = onOpenProfile)
    }
    composable<ProfileDetailRoute> {
        ProfileDetailScreen(
            onBack = onBack,
            onOpenProfile = onOpenProfile,
            onRetest = onRetest,
            onHealth = onHealth,
            onPreferenceTest = onPreferenceTest,
        )
    }
}

internal const val TAG_IMPORT = "profiles_import"
internal const val TAG_CREATE_CURRENT = "profiles_create_current"
internal const val TAG_CONFLICT_REPLACE = "profiles_conflict_replace"
internal const val TAG_CONFLICT_AS_ANY = "profiles_conflict_as_any"
internal const val TAG_CONFLICT_REPLACE_ANY = "profiles_conflict_replace_any"
internal const val TAG_MESSAGE = "profiles_message"

internal fun readLimited(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        // One byte over the limit is enough for the importer to reject the file as too large.
        while (out.size() <= ProfileImportValidator.MAX_BYTES) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
} catch (_: Exception) {
    null
}

@Composable
internal fun ProfilesScreen(
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readLimited(context, uri) }
                viewModel.importBytes(bytes)
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ProfilesEvent.OpenProfile -> onOpenProfile(e.profileId)
            }
        }
    }
    ProfilesContent(
        state = state,
        startWithCreate = viewModel.startWithCreate,
        suggestedName = viewModel.suggestedName(),
        actions = ProfilesActions(
            onBack = onBack,
            onOpenProfile = onOpenProfile,
            onImport = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onCreateListener = viewModel::createListener,
            onRenameListener = viewModel::renameListener,
            onUseListener = viewModel::setActiveListener,
            onAddAny = viewModel::ensureAnyProfile,
            onCreateForCurrent = viewModel::createForCurrentHeadset,
            onResolveConflict = viewModel::resolveConflict,
            onDismissConflict = viewModel::dismissConflict,
            onDismissMessage = viewModel::dismissMessage,
        ),
    )
}

internal class ProfilesActions(
    val onBack: () -> Unit = {},
    val onOpenProfile: (Long) -> Unit = {},
    val onImport: () -> Unit = {},
    val onCreateListener: (String) -> Unit = {},
    val onRenameListener: (Long, String) -> Unit = { _, _ -> },
    val onUseListener: (Long) -> Unit = {},
    val onAddAny: (Long) -> Unit = {},
    val onCreateForCurrent: (listenerId: Long, name: String) -> Unit = { _, _ -> },
    val onResolveConflict: (ConflictPolicy) -> Unit = {},
    val onDismissConflict: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesContent(
    state: ProfilesUiState,
    startWithCreate: Boolean,
    suggestedName: String?,
    actions: ProfilesActions,
) {
    var showNewListener by rememberSaveable { mutableStateOf(false) }
    var renameListenerId by rememberSaveable { mutableLongStateOf(-1L) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    // Deep link: open the create dialog once, as soon as a headset can be created.
    LaunchedEffect(startWithCreate, state.currentStatus, state.loading) {
        if (startWithCreate && !autoOpened && !state.loading) {
            autoOpened = true
            if (state.currentStatus == CurrentHeadsetStatus.READY && state.groups.isNotEmpty()) showCreate = true
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                        PAudioIcon(PAudioIcons.ArrowBack, stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.message?.let { m -> item(key = "message") { MessageCard(m, actions.onDismissMessage) } }
            item(key = "current") {
                CurrentCard(state, onCreate = { showCreate = true })
            }
            item(key = "tools") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showNewListener = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.profiles_add_listener)) }
                    OutlinedButton(
                        onClick = actions.onImport,
                        enabled = !state.importing,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(TAG_IMPORT),
                    ) { Text(stringResource(if (state.importing) R.string.profiles_importing else R.string.profiles_import)) }
                }
            }
            if (state.groups.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.groups, key = { "listener-${it.id}" }) { group ->
                ListenerSection(
                    group,
                    onOpen = actions.onOpenProfile,
                    onUse = { actions.onUseListener(group.id) },
                    onRename = { renameListenerId = group.id },
                    onAddAny = { actions.onAddAny(group.id) },
                )
            }
        }
    }

    if (showNewListener) {
        TextInputDialog(
            title = stringResource(R.string.profiles_dialog_new_listener),
            confirmLabel = stringResource(R.string.profiles_create),
            onConfirm = {
                actions.onCreateListener(it)
                showNewListener = false
            },
            onDismiss = { showNewListener = false },
        )
    }
    val renaming = state.groups.firstOrNull { it.id == renameListenerId }
    if (renaming != null) {
        TextInputDialog(
            title = stringResource(R.string.profiles_dialog_rename_listener),
            initial = renaming.name,
            confirmLabel = stringResource(R.string.profiles_save),
            onConfirm = {
                actions.onRenameListener(renaming.id, it)
                renameListenerId = -1L
            },
            onDismiss = { renameListenerId = -1L },
        )
    }
    if (showCreate) {
        CreateForCurrentDialog(
            state = state,
            suggestedName = suggestedName.orEmpty(),
            onCreate = { listenerId, name ->
                actions.onCreateForCurrent(listenerId, name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    state.conflict?.let { c ->
        ConflictDialog(c.existingProfileName, c.anySlot, state.conflictOptions, actions.onResolveConflict, actions.onDismissConflict)
    }
}

@Composable
private fun MessageCard(message: ProfilesMessage, onDismiss: () -> Unit) {
    val isError = message is ProfilesMessage.ImportFailed || message == ProfilesMessage.Failed ||
        message == ProfilesMessage.ProfileExists || message == ProfilesMessage.ListenerExists
    val text = when (message) {
        is ProfilesMessage.Imported ->
            stringResource(
                if (message.replaced) R.string.profiles_message_imported_replaced else R.string.profiles_message_imported,
                message.profileName,
            )
        is ProfilesMessage.ImportFailed -> stringResource(importErrorRes(message.reason))
        ProfilesMessage.ProfileExists -> stringResource(R.string.profiles_message_profile_exists)
        ProfilesMessage.ListenerExists -> stringResource(R.string.profiles_message_listener_exists)
        ProfilesMessage.Failed -> stringResource(R.string.profiles_message_failed)
    }
    SectionCard(modifier = Modifier.testTag(TAG_MESSAGE)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            PAudioIcon(
                if (isError) PAudioIcons.Warning else PAudioIcons.Check,
                null,
                Modifier.size(24.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(text, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.profiles_message_dismiss))
        }
    }
}

internal fun importErrorRes(reason: ImportErrorReason): Int = when (reason) {
    ImportErrorReason.TOO_LARGE -> R.string.profiles_import_error_too_large
    ImportErrorReason.NOT_UTF8 -> R.string.profiles_import_error_not_utf8
    ImportErrorReason.MALFORMED -> R.string.profiles_import_error_malformed
    ImportErrorReason.TOO_DEEP -> R.string.profiles_import_error_too_deep
    ImportErrorReason.UNSUPPORTED_VERSION -> R.string.profiles_import_error_version
    ImportErrorReason.INVALID_VALUE -> R.string.profiles_import_error_value
    ImportErrorReason.TOO_MANY_TESTS -> R.string.profiles_import_error_too_many_tests
    ImportErrorReason.TOO_MANY_TRIALS -> R.string.profiles_import_error_too_many_trials
    ImportErrorReason.STORAGE -> R.string.profiles_import_error_storage
    ImportErrorReason.IO -> R.string.profiles_import_error_io
}

@Composable
private fun CurrentCard(state: ProfilesUiState, onCreate: () -> Unit) {
    SectionCard(title = stringResource(R.string.profiles_current_title)) {
        when (state.currentStatus) {
            CurrentHeadsetStatus.READY ->
                Text(stringResource(R.string.profiles_current_ready, state.current?.productName.orEmpty().ifBlank { state.current?.type?.name.orEmpty() }))
            CurrentHeadsetStatus.NOT_PROCESSABLE -> Text(stringResource(R.string.profiles_current_speaker))
            CurrentHeadsetStatus.NONE -> Text(stringResource(R.string.profiles_current_none))
        }
        Button(
            onClick = onCreate,
            enabled = state.currentStatus == CurrentHeadsetStatus.READY && state.groups.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TAG_CREATE_CURRENT),
        ) { Text(stringResource(R.string.profiles_create_for_headset)) }
    }
}

@Composable
private fun ListenerSection(
    group: ListenerGroup,
    onOpen: (Long) -> Unit,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onAddAny: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (group.isActive) {
                StatusChip(stringResource(R.string.profiles_listener_active), StatusTone.Ok, icon = PAudioIcons.Check)
            } else {
                OutlinedButton(onClick = onUse, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.profiles_listener_use))
                }
            }
            IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) {
                PAudioIcon(PAudioIcons.Edit, stringResource(R.string.profiles_listener_rename, group.name))
            }
        }
        group.profiles.forEach { p -> ProfileRow(p, onOpen) }
        if (!group.hasAnyProfile) {
            OutlinedButton(onClick = onAddAny, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profiles_add_any))
            }
        }
    }
}

@Composable
private fun ProfileRow(item: ProfileItem, onOpen: (Long) -> Unit) {
    val headset = when (val h = item.headset) {
        is HeadsetLabel.Named -> h.name
        HeadsetLabel.AnyHeadphones -> stringResource(R.string.profiles_any)
    }
    val description = stringResource(R.string.profiles_open_profile, item.name)
    Surface(
        onClick = { onOpen(item.id) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { contentDescription = "$description, $headset" },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PAudioIcon(
                if (item.headset is HeadsetLabel.Named) PAudioIcons.Headphones else PAudioIcons.Earbuds,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(headset, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PAudioIcon(PAudioIcons.ChevronRight, null, Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ProfilesViewModel.MAX_NAME) },
                label = { Text(stringResource(R.string.profiles_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}

@Composable
private fun CreateForCurrentDialog(
    state: ProfilesUiState,
    suggestedName: String,
    onCreate: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    val defaultListener = state.groups.firstOrNull { it.isActive }?.id ?: state.groups.firstOrNull()?.id ?: -1L
    var listenerId by rememberSaveable { mutableLongStateOf(defaultListener) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_dialog_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ProfilesViewModel.MAX_NAME) },
                    label = { Text(stringResource(R.string.profiles_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.groups.size > 1) {
                    Text(stringResource(R.string.profiles_dialog_listener_label), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        state.groups.forEach { g ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = g.id == listenerId, onClick = { listenerId = g.id }, role = Role.RadioButton),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(selected = g.id == listenerId, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                                Text(g.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(listenerId, name) }, enabled = listenerId >= 0, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profiles_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}

@Composable
private fun ConflictDialog(
    existingName: String,
    anySlot: Boolean,
    options: List<ConflictPolicy>,
    onResolve: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_conflict_title)) },
        text = {
            Text(stringResource(if (anySlot) R.string.profiles_conflict_body_any else R.string.profiles_conflict_body, existingName))
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                options.forEach { policy ->
                    val (label, tag) = when (policy) {
                        ConflictPolicy.REPLACE -> R.string.profiles_conflict_replace to TAG_CONFLICT_REPLACE
                        ConflictPolicy.IMPORT_AS_ANY -> R.string.profiles_conflict_as_any to TAG_CONFLICT_AS_ANY
                        ConflictPolicy.REPLACE_ANY -> R.string.profiles_conflict_replace_any to TAG_CONFLICT_REPLACE_ANY
                        ConflictPolicy.ASK -> return@forEach
                    }
                    TextButton(onClick = { onResolve(policy) }, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
                        Text(stringResource(label))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.profiles_cancel)) }
        },
    )
}
