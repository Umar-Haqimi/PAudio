package my.umarh.paudio.feature.health

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.settings.SettingsRepository

@HiltViewModel
class HealthViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val tests: HearingTestRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<HealthRoute>()

    /** The explicitly requested test; cleared by [showLatest]. null = latest. */
    private val requestedTestId = MutableStateFlow(route.testId)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HealthUiState> = profileIdFlow()
        .flatMapLatest { profileId ->
            if (profileId == null) flowOf(HealthUiState.Empty) else stateFor(profileId, explicit = route.profileId != null)
        }
        .catch { emit(HealthUiState.Unavailable(UnavailableReason.ERROR)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState.Loading)

    /** Drops the explicit test request and shows the most recent test of the profile. */
    fun showLatest() {
        requestedTestId.value = null
    }

    /**
     * Route param when given; otherwise the most recently tested profile (active listener's first, then any).
     * Reactive: follows new tests and profile changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun profileIdFlow(): Flow<Long?> {
        route.profileId?.let { return flowOf(it) }
        return combine(profiles.observeAll(), settings.activeListenerId) { all, active -> all to active }
            .flatMapLatest { (all, active) ->
                val ordered = all.filter { it.listener.id == active } + all.filter { it.listener.id != active }
                val groups = ordered.map { g -> g.profiles.map { it.profile.id } }
                // Listeners are tried in order; the first listener with any test wins, then its most recent profile.
                val perProfile = ordered.flatMap { g -> g.profiles.map { it.profile.id } }
                if (perProfile.isEmpty()) {
                    flowOf(null)
                } else {
                    combine(perProfile.map { id -> tests.observeLatest(id) }) { latest ->
                        val startedAt = perProfile.indices.associate { perProfile[it] to latest[it]?.startedAt }
                        groups.firstNotNullOfOrNull { ids ->
                            ids.mapNotNull { id -> startedAt[id]?.let { id to it } }.maxByOrNull { it.second }?.first
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stateFor(profileId: Long, explicit: Boolean): Flow<HealthUiState> =
        combine(profiles.observe(profileId), tests.observeForProfile(profileId), requestedTestId) { profile, all, requested ->
            Triple(profile, all, requested)
        }.flatMapLatest { (profile, all, requested) ->
            val latest = all.maxByOrNull { it.startedAt }
            when {
                explicit && profile == null -> flowOf(HealthUiState.Unavailable(UnavailableReason.PROFILE_MISSING))
                requested != null && all.none { it.id == requested } ->
                    flowOf(HealthUiState.Unavailable(UnavailableReason.TEST_MISSING, canShowLatest = latest != null))
                latest == null -> flowOf(HealthUiState.Empty)
                else -> {
                    val selected = if (requested == null) latest else all.first { it.id == requested }
                    val key = DeviceSnapshot.headsetKeyOf(selected.deviceSnapshotJson) ?: profile?.headset?.key
                    val trendFlow =
                        if (key == null) flowOf(listOf(selected)) else tests.observeForTrend(profileId, HeadsetKey(key))
                    combine(tests.observeThresholds(selected.id), trendFlow) { thresholds, trendTests ->
                        val trend = trendTests
                            .mapNotNull { t -> HealthMapper.trendPoint(t, tests.thresholds(t.id)) }
                            .sortedBy { it.dateMillis }
                        HealthMapper.content(selected, thresholds, trend)
                            ?.let { HealthUiState.Content(it) }
                            ?: HealthUiState.Unavailable(UnavailableReason.INCOMPLETE, canShowLatest = selected.id != latest.id)
                    }
                }
            }
        }
}
