package my.umarh.paudio.feature.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.umarh.paudio.core.model.OutputDevice
import my.umarh.paudio.core.model.OutputDevices
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.ListenerRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportErrorReason
import my.umarh.paudio.data.transfer.ImportResult

/** How a profile's headset is shown. */
sealed interface HeadsetLabel {
    data class Named(val name: String) : HeadsetLabel
    data object AnyHeadphones : HeadsetLabel
}

@Immutable
data class ProfileItem(val id: Long, val name: String, val headset: HeadsetLabel)

@Immutable
data class ListenerGroup(
    val id: Long,
    val name: String,
    val isActive: Boolean,
    val profiles: List<ProfileItem>,
) {
    val hasAnyProfile: Boolean get() = profiles.any { it.headset == HeadsetLabel.AnyHeadphones }
}

/** Why a profile cannot be created for the current output. */
enum class CurrentHeadsetStatus { NONE, NOT_PROCESSABLE, READY }

/** Outcome of the last user action, shown inline. */
sealed interface ProfilesMessage {
    data class Imported(val profileName: String, val replaced: Boolean) : ProfilesMessage
    data class ImportFailed(val reason: ImportErrorReason) : ProfilesMessage
    data object ProfileExists : ProfilesMessage
    data object ListenerExists : ProfilesMessage
    data object Failed : ProfilesMessage
}

@Immutable
data class ProfilesUiState(
    val loading: Boolean = true,
    val groups: List<ListenerGroup> = emptyList(),
    val current: OutputDevice? = null,
    val currentStatus: CurrentHeadsetStatus = CurrentHeadsetStatus.NONE,
    val message: ProfilesMessage? = null,
    /** Set while the user has to choose how to resolve an import conflict. */
    val conflict: ImportResult.Conflict? = null,
    /** The policies that make sense for [conflict] (see `conflictOptions`). */
    val conflictOptions: List<ConflictPolicy> = emptyList(),
    val importing: Boolean = false,
)

sealed interface ProfilesEvent {
    data class OpenProfile(val profileId: Long) : ProfilesEvent
}

/** Export/import seam over `ProfileExporter` / `ProfileImporter` (faked in tests). */
interface ProfileTransfer {
    suspend fun export(profileId: Long): String?
    suspend fun import(bytes: ByteArray, policy: ConflictPolicy): ImportResult
}

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val listeners: ListenerRepository,
    private val headsets: HeadsetRepository,
    private val settings: SettingsRepository,
    private val devices: OutputDevices,
    private val transfer: ProfileTransfer,
) : ViewModel() {
    /** The "Create profile for this headset" flow was requested (notification, Home). */
    val startWithCreate: Boolean = savedStateHandle.get<Boolean>("createForCurrent") ?: false

    private val local = MutableStateFlow(Local())
    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events: Flow<ProfilesEvent> = _events.receiveAsFlow()

    /** Bytes of the file whose import ended in a conflict (kept to retry with another policy). */
    private var pendingBytes: ByteArray? = null

    private data class Local(
        val message: ProfilesMessage? = null,
        val conflict: ImportResult.Conflict? = null,
        val lastPolicy: ConflictPolicy = ConflictPolicy.ASK,
        val importing: Boolean = false,
    )

    val uiState: StateFlow<ProfilesUiState> = combine(
        profiles.observeAll(),
        settings.activeListenerId,
        devices.current,
        local,
    ) { all, activeId, device, l ->
        val active = all.firstOrNull { it.listener.id == activeId } ?: all.firstOrNull()
        ProfilesUiState(
            loading = false,
            groups = all.map { g ->
                ListenerGroup(
                    id = g.listener.id,
                    name = g.listener.name,
                    isActive = g.listener.id == active?.listener?.id,
                    profiles = g.profiles
                        .sortedWith(compareBy({ it.headset != null }, { it.profile.name.lowercase() }))
                        .map { p ->
                            ProfileItem(
                                p.profile.id,
                                p.profile.name,
                                p.headset?.let { HeadsetLabel.Named(it.displayName) } ?: HeadsetLabel.AnyHeadphones,
                            )
                        },
                )
            },
            current = device,
            currentStatus = when {
                device == null -> CurrentHeadsetStatus.NONE
                !device.isProcessable -> CurrentHeadsetStatus.NOT_PROCESSABLE
                else -> CurrentHeadsetStatus.READY
            },
            message = l.message,
            conflict = l.conflict,
            conflictOptions = l.conflict?.let { conflictOptions(it, l.lastPolicy) }.orEmpty(),
            importing = l.importing,
        )
    }
        .catch { emit(ProfilesUiState(loading = false, message = ProfilesMessage.Failed)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun dismissMessage() = local.update { it.copy(message = null) }

    // ---------------------------------------------------------------- listeners

    fun createListener(name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return
        launchGuarded {
            if (listeners.observeAll().first().any { it.name.equals(trimmed, ignoreCase = true) }) {
                local.update { it.copy(message = ProfilesMessage.ListenerExists) }
                return@launchGuarded
            }
            val id = listeners.create(trimmed)
            profiles.ensureAnyHeadphonesProfile(id)
        }
    }

    fun renameListener(id: Long, name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return
        launchGuarded { listeners.rename(id, trimmed) }
    }

    /** Switches the active listener (F16): the profile of that listener is used from now on. */
    fun setActiveListener(id: Long) {
        launchGuarded { settings.setActiveListenerId(id) }
    }

    // ---------------------------------------------------------------- profiles

    /** "Any headphones" profile for [listenerId] (created when missing). */
    fun ensureAnyProfile(listenerId: Long) {
        launchGuarded { profiles.ensureAnyHeadphonesProfile(listenerId) }
    }

    /** Suggested name for a profile created for the current headset. */
    fun suggestedName(): String? = devices.current.value?.productName?.takeIf { it.isNotBlank() }

    /** Creates a profile of [listenerId] bound to the headset that is connected right now. */
    fun createForCurrentHeadset(listenerId: Long, name: String) {
        val device = devices.current.value ?: return
        if (!device.isProcessable) return
        val finalName = name.trim().take(MAX_NAME).ifBlank { device.productName.ifBlank { device.type.name } }
        launchGuarded {
            val headsetId = headsets.getOrCreate(device)
            val strength = settings.defaultCompStrength.first()
            when (val r = profiles.create(listenerId, headsetId, finalName, ProfileSettings(compStrength = strength))) {
                is ProfileWriteResult.Ok -> _events.send(ProfilesEvent.OpenProfile(r.profileId))
                is ProfileWriteResult.Conflict -> local.update { it.copy(message = ProfilesMessage.ProfileExists) }
                ProfileWriteResult.NotFound -> local.update { it.copy(message = ProfilesMessage.Failed) }
            }
        }
    }

    // ---------------------------------------------------------------- import

    /** [bytes] null = the file could not be read. */
    fun importBytes(bytes: ByteArray?) {
        if (bytes == null) {
            local.update { it.copy(message = ProfilesMessage.ImportFailed(ImportErrorReason.IO)) }
            return
        }
        pendingBytes = bytes
        runImport(ConflictPolicy.ASK)
    }

    /** Resolves the open conflict dialog with [policy] (REPLACE, IMPORT_AS_ANY or REPLACE_ANY). */
    fun resolveConflict(policy: ConflictPolicy) {
        if (policy == ConflictPolicy.ASK) return
        local.update { it.copy(conflict = null) }
        runImport(policy)
    }

    fun dismissConflict() {
        pendingBytes = null
        local.update { it.copy(conflict = null) }
    }

    private fun runImport(policy: ConflictPolicy) {
        val bytes = pendingBytes ?: return
        local.update { it.copy(importing = true, message = null, lastPolicy = policy) }
        viewModelScope.launch {
            try {
                when (val r = transfer.import(bytes, policy)) {
                    is ImportResult.Success -> {
                        pendingBytes = null
                        val name = profiles.get(r.profileId)?.profile?.name.orEmpty()
                        local.update {
                            it.copy(importing = false, message = ProfilesMessage.Imported(name, r.replacedProfileId != null))
                        }
                        _events.send(ProfilesEvent.OpenProfile(r.profileId))
                    }
                    is ImportResult.Conflict -> local.update { it.copy(importing = false, conflict = r) }
                    is ImportResult.Error -> {
                        pendingBytes = null
                        local.update { it.copy(importing = false, message = ProfilesMessage.ImportFailed(r.reason)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pendingBytes = null
                local.update { it.copy(importing = false, message = ProfilesMessage.Failed) }
            }
        }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(message = ProfilesMessage.Failed) }
            }
        }
    }

    companion object {
        const val MAX_NAME = 40

        /**
         * Resolutions offered for a conflict, depending on the policy that produced it:
         *  - first attempt, headset slot taken: REPLACE it, or IMPORT_AS_ANY;
         *  - first attempt, "Any headphones" slot taken (the file has no headset): REPLACE;
         *  - after IMPORT_AS_ANY, "Any headphones" taken: REPLACE_ANY.
         */
        fun conflictOptions(conflict: ImportResult.Conflict, lastPolicy: ConflictPolicy): List<ConflictPolicy> = when {
            lastPolicy == ConflictPolicy.IMPORT_AS_ANY -> listOf(ConflictPolicy.REPLACE_ANY)
            conflict.anySlot -> listOf(ConflictPolicy.REPLACE)
            else -> listOf(ConflictPolicy.REPLACE, ConflictPolicy.IMPORT_AS_ANY)
        }
    }
}
