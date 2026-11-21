package my.umarh.paudio.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import my.umarh.paudio.audio.effect.EngineState
import my.umarh.paudio.core.audiometry.MeasurementModel
import my.umarh.paudio.core.audiometry.ThresholdPoint
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.FrequencyGrid
import my.umarh.paudio.data.db.ThresholdEntity
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ListenerProfiles
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.data.repo.ProfileSettings
import my.umarh.paudio.data.repo.ProfileWithHeadset
import my.umarh.paudio.data.settings.SettingsRepository
import my.umarh.paudio.service.engine.ActiveProfile

/** Mini butterfly input: HL_est per ear in [freqsHz] order. */
@Immutable
data class HomeButterfly(val leftHl: List<Float?>, val rightHl: List<Float?>, val freqsHz: List<Int>)

/** What the active-profile card shows about the headset. */
sealed interface HeadsetLabel {
    data class Named(val name: String) : HeadsetLabel
    data object AnyHeadphones : HeadsetLabel
}

@Immutable
data class HomeProfile(
    val profileId: Long,
    val profileName: String,
    val listenerName: String,
    val headset: HeadsetLabel,
    val strength: Float,
    val butterfly: HomeButterfly?,
    /** The profile has at least one saved hearing test. */
    val hasTest: Boolean,
)

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val engineState: EngineState = EngineState.Off,
    val noSessionHint: Boolean = false,
    /** An unexpected pipeline failure is being retried. */
    val fault: Boolean = false,
    val notificationsBlocked: Boolean = false,
    /** The profile shown on the card (the resolved one, else the active listener's Any/first profile). */
    val profile: HomeProfile? = null,
    /** The engine follows [profile] right now, so the Personalized/Original toggle acts on it. */
    val engineFollowsProfile: Boolean = false,
    val personalized: Boolean = false,
    /** The engine sees a processable headset the active listener has no profile for. */
    val noProfileForHeadset: Boolean = false,
) {
    /** No profile or no test yet: the empty state with one big call to action. */
    val isEmpty: Boolean get() = !loading && (profile == null || !profile.hasTest)
}

/** What Home needs from the engine (implemented by an adapter over `EngineController`; faked in tests). */
interface HomeEngine {
    val state: StateFlow<EngineState>
    val noSessionHint: StateFlow<Boolean>
    val running: StateFlow<Boolean>
    val activeProfile: StateFlow<ActiveProfile>
    val notificationsBlocked: StateFlow<Boolean>
    val fault: StateFlow<Boolean>
    fun start(): Boolean
    fun retry()
    suspend fun setPersonalized(on: Boolean): Boolean
    suspend fun togglePersonalized(): Boolean
    fun refreshNotificationPermission()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val engine: HomeEngine,
    private val profiles: ProfileRepository,
    private val tests: HearingTestRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val profileFlow: Flow<HomeProfile?> = combine(
        engine.activeProfile,
        settings.activeListenerId,
        profiles.observeAll(),
    ) { active, listenerId, groups -> pickProfile(active, listenerId, groups) }
        .distinctUntilChanged()
        .flatMapLatest { picked ->
            if (picked == null) {
                flowOf(null)
            } else {
                tests.observeForProfile(picked.profile.profile.id).flatMapLatest { all ->
                    val latest = all.maxByOrNull { it.startedAt }
                    if (latest == null) {
                        flowOf(picked.toHome(butterfly = null, hasTest = false))
                    } else {
                        tests.observeThresholds(latest.id).map { th -> picked.toHome(butterfly(th), hasTest = true) }
                    }
                }
            }
        }

    private val engineFlow = combine(
        engine.state,
        engine.noSessionHint,
        engine.running,
        engine.notificationsBlocked,
        engine.fault,
    ) { state, hint, running, blocked, fault -> EngineBits(state, hint, running, blocked, fault) }

    val uiState: StateFlow<HomeUiState> = combine(engineFlow, engine.activeProfile, profileFlow) { e, active, profile ->
        HomeUiState(
            loading = false,
            running = e.running,
            engineState = e.state,
            noSessionHint = e.hint,
            fault = e.fault,
            notificationsBlocked = e.blocked,
            profile = profile,
            engineFollowsProfile = active is ActiveProfile.Resolved,
            personalized = (active as? ActiveProfile.Resolved)?.profile?.personalizationOn ?: false,
            noProfileForHeadset = active is ActiveProfile.NoProfile,
        )
    }
        .catch { emit(HomeUiState(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setPersonalized(on: Boolean) {
        viewModelScope.launch { engine.setPersonalized(on) }
    }

    fun togglePersonalized() {
        viewModelScope.launch { engine.togglePersonalized() }
    }

    /** Persists the compensation strength of the displayed profile (call when the slider is released). */
    fun setStrength(strength: Float) {
        val id = uiState.value.profile?.profileId ?: return
        viewModelScope.launch {
            try {
                // Re-read so a concurrent Personalized/Original change is not overwritten.
                val p = profiles.get(id)?.profile ?: return@launch
                profiles.updateSettings(
                    id,
                    ProfileSettings(strength.coerceIn(0f, 1f), p.gB, p.gP, p.gT, p.personalizationOn),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the previous value; the stored flow re-emits it.
            }
        }
    }

    /** Tap on a Conflict/Error chip. */
    fun retry() = engine.retry()

    /** "Turn on" while the service is off. */
    fun turnOn() {
        engine.start()
    }

    fun refreshNotificationPermission() = engine.refreshNotificationPermission()

    private data class EngineBits(
        val state: EngineState,
        val hint: Boolean,
        val running: Boolean,
        val blocked: Boolean,
        val fault: Boolean,
    )

    private data class Picked(val profile: ProfileWithHeadset, val listenerName: String)

    private fun Picked.toHome(butterfly: HomeButterfly?, hasTest: Boolean) = HomeProfile(
        profileId = profile.profile.id,
        profileName = profile.profile.name,
        listenerName = listenerName,
        headset = profile.headset?.let { HeadsetLabel.Named(it.displayName) } ?: HeadsetLabel.AnyHeadphones,
        strength = profile.profile.compStrength,
        butterfly = butterfly,
        hasTest = hasTest,
    )

    companion object {
        private fun pickProfile(active: ActiveProfile, listenerId: Long?, groups: List<ListenerProfiles>): Picked? {
            if (active is ActiveProfile.Resolved) {
                val group = groups.firstOrNull { it.listener.id == active.profile.listenerId }
                val found = group?.profiles?.firstOrNull { it.profile.id == active.profile.id }
                if (found != null) return Picked(found, group.listener.name)
            }
            val group = groups.firstOrNull { it.listener.id == listenerId } ?: groups.firstOrNull() ?: return null
            val p = group.profiles.firstOrNull { it.headset == null } ?: group.profiles.firstOrNull() ?: return null
            return Picked(p, group.listener.name)
        }

        /** HL_est per ear from raw thresholds; null when the set is incomplete (as the hearing-health screen). */
        fun butterfly(thresholds: List<ThresholdEntity>): HomeButterfly? {
            val byEar = Ear.entries.associateWith { ear ->
                thresholds.filter { it.ear == ear && it.levelDbfs != null }.associateBy { it.freqHz }
            }
            if (!byEar.values.all { pts -> FrequencyGrid.TEST_FREQUENCIES_HZ.all { it in pts } }) return null
            val points = byEar.mapValues { (_, pts) ->
                FrequencyGrid.TEST_FREQUENCIES_HZ.map { f ->
                    val t = pts.getValue(f)
                    ThresholdPoint(f, t.levelDbfs!!.toDouble(), t.censored)
                }
            }
            val r = MeasurementModel.compute(points)
            fun hl(ear: Ear): List<Float?> = r.hlEst.getValue(ear).map { it.toFloat() }
            return HomeButterfly(hl(Ear.LEFT), hl(Ear.RIGHT), r.freqsHz)
        }
    }
}
