package my.umarh.paudio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.umarh.paudio.data.db.ProfileEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.feature.profiles.ProfileMeasure
import my.umarh.paudio.service.LaunchRequests
import my.umarh.paudio.service.engine.ActiveProfile
import my.umarh.paudio.service.engine.EngineController

/** Where the nav host starts; decided once, after the first read of the persisted "onboarding done" flag. */
sealed interface AppStart {
    data object Loading : AppStart
    data object Onboarding : AppStart
    data object Home : AppStart
}

@Immutable
data class WhichHeadphonesUi(val candidates: List<Candidate>) {
    data class Candidate(val profileId: Long, val profileName: String, val listenerName: String)
}

/** App-level state: start destination, profile-seeded theme (F25), launch routes, the wired-collision question. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val tests: HearingTestRepository,
    private val engine: EngineController,
    private val launchRequests: LaunchRequests,
) : ViewModel() {
    private val _start = MutableStateFlow<AppStart>(AppStart.Loading)
    val start: StateFlow<AppStart> = _start

    /** The route a notification asked for (`ServiceIntents.ROUTE_*`), until [consumeLaunchRoute]. */
    val launchRoute: StateFlow<String?> = launchRequests.pending

    /** The profile whose seed colour themes the app: the resolved one, else the active listener's Any/first. */
    private val themeProfile: Flow<ProfileEntity?> =
        combine(engine.activeProfile, settings.activeListenerId, profiles.observeAll()) { active, listenerId, groups ->
            pickThemeProfile(active, listenerId, groups)
        }.distinctUntilChanged()

    /** null = no seed: the theme falls back to wallpaper dynamic colour, then the brand scheme. */
    val seedColor: StateFlow<Color?> = themeProfile
        .map { p -> p?.seedColor?.let { Color(it) } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val whichHeadphones: StateFlow<WhichHeadphonesUi?> =
        combine(engine.whichHeadphones, profiles.observeAll()) { question, groups ->
            question?.let { q ->
                val byProfile = groups.flatMap { g -> g.profiles.map { it.profile.id to g.listener.name } }.toMap()
                WhichHeadphonesUi(q.candidates.map { WhichHeadphonesUi.Candidate(it.id, it.name, byProfile[it.id].orEmpty()) })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            _start.value = if (settings.onboardingDone.first()) AppStart.Home else AppStart.Onboarding
        }
        viewModelScope.launch {
            seedSync(themeProfile, tests).collect { (id, seed) ->
                // A failed write must not end the collector (it is the only app-level seed writer); no personal data logged.
                try {
                    profiles.setSeedColor(id, seed, false)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "seed colour sync failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    fun consumeLaunchRoute() = launchRequests.consume()

    /** `null` keeps the default resolution ("Not now"). */
    fun answerWhichHeadphones(profileId: Long?) = engine.answerWhichHeadphones(profileId)

    private companion object {
        const val TAG = "AppViewModel"
    }
}

internal fun pickThemeProfile(active: ActiveProfile, listenerId: Long?, groups: List<ListenerProfiles>): ProfileEntity? {
    if (active is ActiveProfile.Resolved) return active.profile
    val group = groups.firstOrNull { it.listener.id == listenerId } ?: groups.firstOrNull() ?: return null
    return (group.profiles.firstOrNull { it.headset == null } ?: group.profiles.firstOrNull())?.profile
}

/**
 * Keeps the stored seed colour of the themed profile in step with its latest complete test (F25), unless the
 * user picked a colour (`seedOverridden`). Emits (profileId, seed) only when the stored value is stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun seedSync(themeProfile: Flow<ProfileEntity?>, tests: HearingTestRepository): Flow<Pair<Long, Int>> =
    themeProfile.flatMapLatest { p ->
        if (p == null || p.seedOverridden) {
            flowOf()
        } else {
            tests.observeForProfile(p.id)
                .flatMapLatest { all ->
                    val latest = all.maxByOrNull { it.startedAt }
                    if (latest == null) flowOf<Int?>() else tests.observeThresholds(latest.id).map { th -> ProfileMeasure.seedColor(th) }
                }
                .map { derived -> derived?.takeIf { it != p.seedColor }?.let { p.id to it } }
                .filterNotNull()
        }
    }.catch { t -> Log.w("AppViewModel", "seed colour observe failed: ${t.javaClass.simpleName}") }
