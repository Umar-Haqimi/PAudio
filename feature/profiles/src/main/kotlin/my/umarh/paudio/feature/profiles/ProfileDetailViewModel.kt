package my.umarh.paudio.feature.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.HeadsetRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSlot
import my.umarh.paudio.data.repo.ProfileWriteResult
import my.umarh.paudio.data.settings.SettingsRepository

@Immutable
data class TestRow(val id: Long, val startedAt: Long, val mode: TestMode, val reliable: Boolean)

/** A free (listener, headset) slot the profile can be duplicated or moved to. */
@Immutable
data class SlotOption(val headsetId: Long?, val label: HeadsetLabel)

sealed interface DetailMessage {
    data object SlotTaken : DetailMessage
    data object Failed : DetailMessage
    data object Exported : DetailMessage
    data object ExportFailed : DetailMessage
}

@Immutable
data class ProfileDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val profileId: Long = 0,
    val name: String = "",
    val listenerId: Long = 0,
    val listenerName: String = "",
    val headset: HeadsetLabel = HeadsetLabel.AnyHeadphones,
    val butterfly: ButterflyData? = null,
    val tests: List<TestRow> = emptyList(),
    val seedColor: Int? = null,
    val seedOverridden: Boolean = false,
    /** Free slots of this listener (Any + headsets without a profile); the profile's own slot is excluded. */
    val freeSlots: List<SlotOption> = emptyList(),
    val message: DetailMessage? = null,
    /** After a move: offer a retest on the new headset. */
    val offerRetest: Boolean = false,
)

sealed interface DetailEvent {
    /** The profile was deleted; leave the screen. */
    data object Deleted : DetailEvent

    /** A duplicate was created; open it. */
    data class OpenProfile(val profileId: Long) : DetailEvent
}

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val tests: HearingTestRepository,
    private val headsets: HeadsetRepository,
    private val settings: SettingsRepository,
    private val transfer: ProfileTransfer,
) : ViewModel() {
    private val profileId: Long = savedStateHandle.toRoute<ProfileDetailRoute>().profileId

    private data class Local(val message: DetailMessage? = null, val offerRetest: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val data: Flow<ProfileDetailUiState> = combine(
        profiles.observe(profileId),
        profiles.observeAll(),
        headsets.observeAll(),
        tests.observeForProfile(profileId),
    ) { p, all, allHeadsets, testList -> Quad(p, all, allHeadsets, testList) }
        .flatMapLatest { (p, all, allHeadsets, testList) ->
            if (p == null) return@flatMapLatest flowOf(ProfileDetailUiState(loading = false, notFound = true, profileId = profileId))
            val group: ListenerProfiles? = all.firstOrNull { it.listener.id == p.profile.listenerId }
            val usedHeadsetIds = group?.profiles.orEmpty().mapNotNull { it.profile.headsetId }.toSet()
            val hasAny = group?.profiles.orEmpty().any { it.profile.headsetId == null }
            val free = buildList {
                if (!hasAny) add(SlotOption(null, HeadsetLabel.AnyHeadphones))
                allHeadsets.filter { it.id !in usedHeadsetIds }.sortedBy { it.displayName.lowercase() }
                    .forEach { add(SlotOption(it.id, HeadsetLabel.Named(it.displayName))) }
            }
            val sorted = testList.sortedByDescending { it.startedAt }
            val rows = sorted.map { TestRow(it.id, it.startedAt, it.mode, it.reliable) }
            val base = ProfileDetailUiState(
                loading = false,
                profileId = profileId,
                name = p.profile.name,
                listenerId = p.profile.listenerId,
                listenerName = group?.listener?.name.orEmpty(),
                headset = p.headset?.let { HeadsetLabel.Named(it.displayName) } ?: HeadsetLabel.AnyHeadphones,
                tests = rows,
                seedColor = p.profile.seedColor,
                seedOverridden = p.profile.seedOverridden,
                freeSlots = free,
            )
            val latest = sorted.firstOrNull()
            if (latest == null) {
                flowOf(base)
            } else {
                tests.observeThresholds(latest.id).map { th -> base.copy(butterfly = ProfileMeasure.butterfly(th)) }
            }
        }

    val uiState: StateFlow<ProfileDetailUiState> = combine(data, local) { d, l ->
        d.copy(message = l.message, offerRetest = l.offerRetest)
    }
        .catch { emit(ProfileDetailUiState(loading = false, profileId = profileId, message = DetailMessage.Failed)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileDetailUiState(profileId = profileId))

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun dismissRetestOffer() = local.update { it.copy(offerRetest = false) }

    fun rename(name: String) {
        val n = name.trim().take(ProfilesViewModel.MAX_NAME)
        if (n.isEmpty()) return
        guarded { profiles.rename(profileId, n) }
    }

    /** Duplicates settings and the whole test history into [target] (must be a free slot of this listener). */
    fun duplicate(newName: String, target: SlotOption) {
        val n = newName.trim().take(ProfilesViewModel.MAX_NAME)
        val listenerId = uiState.value.listenerId
        if (n.isEmpty()) return
        guarded {
            when (val r = profiles.duplicate(profileId, n, ProfileSlot(listenerId, target.headsetId))) {
                is ProfileWriteResult.Ok -> _events.send(DetailEvent.OpenProfile(r.profileId))
                is ProfileWriteResult.Conflict -> local.update { it.copy(message = DetailMessage.SlotTaken) }
                ProfileWriteResult.NotFound -> local.update { it.copy(message = DetailMessage.Failed) }
            }
        }
    }

    /** Moves the profile to another headset (or Any); on success a retest is offered (F16). */
    fun move(target: SlotOption) {
        guarded {
            when (profiles.moveToHeadset(profileId, target.headsetId)) {
                is ProfileWriteResult.Ok -> {
                    // The remembered headsetKey -> profile entries may point at the old binding.
                    settings.removeProfile(profileId)
                    local.update { it.copy(offerRetest = true) }
                }
                is ProfileWriteResult.Conflict -> local.update { it.copy(message = DetailMessage.SlotTaken) }
                ProfileWriteResult.NotFound -> local.update { it.copy(message = DetailMessage.Failed) }
            }
        }
    }

    fun delete() {
        guarded {
            profiles.delete(profileId)
            settings.removeProfile(profileId)
            _events.send(DetailEvent.Deleted)
        }
    }

    /** The JSON file content for the SAF writer; null when the profile is gone. */
    suspend fun exportBytes(): ByteArray? = try {
        transfer.export(profileId)?.toByteArray(Charsets.UTF_8)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun reportExport(success: Boolean) =
        local.update { it.copy(message = if (success) DetailMessage.Exported else DetailMessage.ExportFailed) }

    /** The user picked a colour: stored with `seedOverridden = true` (F25). */
    fun setSeedColor(argb: Int) {
        guarded { profiles.setSeedColor(profileId, argb, true) }
    }

    /** Back to the colour derived from the latest test (null seed when there is no complete test yet). */
    fun resetSeedColor() {
        guarded {
            val latest = tests.latest(profileId)
            val derived = latest?.let { ProfileMeasure.seedColor(tests.thresholds(it.id)) }
            profiles.setSeedColor(profileId, derived, false)
        }
    }

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update { it.copy(message = DetailMessage.Failed) }
            }
        }
    }
}
