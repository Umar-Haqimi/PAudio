package my.umarh.paudio.feature.health

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.data.db.DeviceSnapshot
import my.umarh.paudio.data.db.HearingTestEntity
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HealthUiState> = flow { emit(resolveProfileId()) }
        .flatMapLatest { profileId -> if (profileId == null) flowOf(HealthUiState.Empty) else stateFor(profileId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState.Loading)

    /** Route param when given; otherwise the most recently tested profile (active listener's first, then any). */
    private suspend fun resolveProfileId(): Long? {
        route.profileId?.let { return it }
        val all = profiles.observeAll().first()
        val active = settings.activeListenerId.first()
        val ordered = all.filter { it.listener.id == active } + all.filter { it.listener.id != active }
        for (group in ordered) {
            val best = group.profiles
                .mapNotNull { p -> tests.latest(p.profile.id)?.let { p.profile.id to it.startedAt } }
                .maxByOrNull { it.second }
            if (best != null) return best.first
        }
        return null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stateFor(profileId: Long): Flow<HealthUiState> =
        combine(profiles.observe(profileId), tests.observeForProfile(profileId)) { profile, all -> profile to all }
            .flatMapLatest { (profile, all) ->
                val selected = selectTest(all)
                if (selected == null) {
                    flowOf(HealthUiState.Empty)
                } else {
                    val key = DeviceSnapshot.headsetKeyOf(selected.deviceSnapshotJson) ?: profile?.headset?.key
                    val trendFlow =
                        if (key == null) flowOf(listOf(selected)) else tests.observeForTrend(profileId, HeadsetKey(key))
                    combine(tests.observeThresholds(selected.id), trendFlow) { thresholds, trendTests ->
                        val trend = trendTests
                            .mapNotNull { t -> HealthMapper.trendPoint(t, tests.thresholds(t.id)) }
                            .sortedBy { it.dateMillis }
                        HealthMapper.content(selected, thresholds, trend)
                            ?.let { HealthUiState.Content(it) } ?: HealthUiState.Empty
                    }
                }
            }

    private fun selectTest(all: List<HearingTestEntity>): HearingTestEntity? =
        route.testId?.let { id -> all.firstOrNull { it.id == id } } ?: all.maxByOrNull { it.startedAt }
}
