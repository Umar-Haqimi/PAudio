package my.umarh.paudio.feature.results

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.umarh.paudio.audio.playback.ClipPlayback
import my.umarh.paudio.audio.playback.DemoClipSource
import my.umarh.paudio.core.dsp.CurveResult
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.data.repo.HearingTestRepository
import my.umarh.paudio.data.repo.ProfileRepository
import my.umarh.paudio.designsystem.ProfileSeedColor

enum class UnavailableReason { TEST_MISSING, PROFILE_MISSING, INCOMPLETE, ERROR }

sealed interface ResultsUiState {
    data object Loading : ResultsUiState
    data class Unavailable(val reason: UnavailableReason) : ResultsUiState
    data class Content(val content: ResultsContent) : ResultsUiState
}

enum class AbSourceKind { DEMO_CLIP, USER_FILE }

enum class AbStatus { IDLE, PREPARING, READY, ERROR }

/** A/B card state. [gLeft]/[gRight] are the Personalized curve G(f) for the EQ graph (null until the profile is loaded). */
@Immutable
class AbUiState(
    val personalized: Boolean = true,
    val sourceKind: AbSourceKind = AbSourceKind.DEMO_CLIP,
    val clip: DemoClip = DemoClip.DISCIPLINE,
    val fileName: String? = null,
    val status: AbStatus = AbStatus.IDLE,
    val playing: Boolean = false,
    /** The last picked file could not be opened; the clip was restored. */
    val fileError: Boolean = false,
    val gLeft: DoubleArray? = null,
    val gRight: DoubleArray? = null,
) {
    fun copy(
        personalized: Boolean = this.personalized,
        sourceKind: AbSourceKind = this.sourceKind,
        clip: DemoClip = this.clip,
        fileName: String? = this.fileName,
        status: AbStatus = this.status,
        playing: Boolean = this.playing,
        fileError: Boolean = this.fileError,
        gLeft: DoubleArray? = this.gLeft,
        gRight: DoubleArray? = this.gRight,
    ) = AbUiState(personalized, sourceKind, clip, fileName, status, playing, fileError, gLeft, gRight)
}

/**
 * Results (F9-F12). Loads one stored test, maps it, and drives the loudness-matched Original/Personalized A/B.
 *
 * P0 (SPEC 2.2 #1): while the A/B is prepared this ViewModel holds its own [BypassToken] (reason RESULTS_AB) in addition
 * to the one the real `ExoAbPlayer` acquires in `load()`; both are ref-counted. [startAb] / [stopAb] are tied to the
 * screen's STARTED lifecycle by the UI, so a screen that is in the back stack, or an app in the background, never leaves
 * the system-wide engine bypassed. Everything here runs on the main thread (ExoPlayer and the engine's `acquire`).
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tests: HearingTestRepository,
    private val profiles: ProfileRepository,
    private val backend: ResultsAbBackend,
    private val bypass: BypassController,
) : ViewModel() {
    private val testId: Long = savedStateHandle.toRoute<ResultsRoute>().testId

    private val _ui = MutableStateFlow<ResultsUiState>(ResultsUiState.Loading)
    val ui: StateFlow<ResultsUiState> = _ui

    private val _ab = MutableStateFlow(AbUiState())
    val ab: StateFlow<AbUiState> = _ab

    private var settings: CurveSettings? = null
    private var curve: CurveResult? = null
    private var plan: AbPlan? = null
    private var handle: AbHandle? = null
    private var token: BypassToken? = null
    private var prepareJob: Job? = null
    private var playingJob: Job? = null
    private var abWanted = false

    /** SAF content:// uri of the user's own file; only held in memory (no persistable permission is taken). */
    private var fileUri: String? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val test = tests.get(testId)
            if (test == null) {
                _ui.value = ResultsUiState.Unavailable(UnavailableReason.TEST_MISSING)
                return
            }
            val content = ResultsMapper.content(test, tests.thresholds(testId))
            if (content == null) {
                _ui.value = ResultsUiState.Unavailable(UnavailableReason.INCOMPLETE)
                return
            }
            val profile = profiles.get(test.profileId)
            if (profile == null) {
                _ui.value = ResultsUiState.Unavailable(UnavailableReason.PROFILE_MISSING)
                return
            }
            val s = CurveSettings(
                strength = profile.profile.compStrength.toDouble().coerceIn(0.0, 1.0),
                gB = profile.profile.gB.toDouble(),
                gP = profile.profile.gP.toDouble(),
                gT = profile.profile.gT.toDouble(),
            )
            val g = AbPlanner.curve(content, s)
            settings = s
            curve = g
            _ab.update { it.copy(gLeft = g.gLeft, gRight = g.gRight) }
            _ui.value = ResultsUiState.Content(content)
            applySeedColor(content, profile.profile.seedOverridden)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            _ui.value = ResultsUiState.Unavailable(UnavailableReason.ERROR)
        }
    }

    /** F25: the newest test's curve colours the profile unless the user overrode the seed. Best effort. */
    private suspend fun applySeedColor(content: ResultsContent, overridden: Boolean) {
        if (overridden) return
        try {
            if (tests.latest(content.profileId)?.id != content.testId) return
            val color = ProfileSeedColor.from(content.leftDisplay, content.rightDisplay, content.frequenciesHz)
            profiles.setSeedColor(content.profileId, color.toArgb(), overridden = false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    // ---- A/B -------------------------------------------------------------------------------------------------------

    /** Call when the screen becomes STARTED. Idempotent. Does not start playback. */
    fun startAb() {
        abWanted = true
        launchPrepare()
    }

    /**
     * Call when the screen stops (background, or leaves the composition): pauses, releases the player and closes the
     * bypass token(s) so the system-wide engine is restored. Idempotent.
     */
    fun stopAb() {
        abWanted = false
        prepareJob?.cancel()
        prepareJob = null
        playingJob?.cancel()
        playingJob = null
        val h = handle
        handle = null
        plan = null
        if (h != null) {
            runCatching { h.player.pause() }
            runCatching { h.player.release() }
        }
        val t = token
        token = null
        runCatching { t?.close() }
        _ab.update { it.copy(status = AbStatus.IDLE, playing = false) }
    }

    fun setPersonalized(on: Boolean) {
        _ab.update { it.copy(personalized = on) }
        if (_ab.value.status == AbStatus.READY) applyCurrent()
    }

    fun togglePlay() {
        val h = handle ?: return
        if (_ab.value.status != AbStatus.READY) return
        if (_ab.value.playing) {
            h.player.pause()
            if (h.playing == null) _ab.update { it.copy(playing = false) }
        } else {
            h.player.play()
            if (h.playing == null) _ab.update { it.copy(playing = true) }
        }
    }

    fun pause() {
        val h = handle ?: return
        h.player.pause()
        if (h.playing == null) _ab.update { it.copy(playing = false) }
    }

    /** User picked a file through the SAF picker ([uri] is a content:// string); pink-noise loudness model. */
    fun useOwnFile(uri: String, displayName: String?) {
        pause()
        fileUri = uri
        _ab.update { it.copy(sourceKind = AbSourceKind.USER_FILE, fileName = displayName, fileError = false) }
        launchPrepare()
    }

    fun retryAb() = launchPrepare()

    fun useDemoClip() {
        pause()
        fileUri = null
        _ab.update { it.copy(sourceKind = AbSourceKind.DEMO_CLIP, fileName = null, fileError = false) }
        launchPrepare()
    }

    private fun launchPrepare() {
        if (!abWanted) return
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        _ui.first { it !is ResultsUiState.Loading }
        val g = curve ?: return
        _ab.update { it.copy(status = AbStatus.PREPARING) }
        val state = _ab.value
        val source: ClipPlayback
        val clip: DemoClip?
        val uri = fileUri
        if (state.sourceKind == AbSourceKind.USER_FILE && uri != null) {
            source = DemoClipSource.userFile(uri)
            clip = null
        } else {
            source = DemoClipSource.results(state.clip)
            clip = state.clip
        }
        try {
            val predictor = backend.predictor(clip)
            val p = AbPlanner.plan(g, backend.outputSampleRate(), predictor)
            // P0: bypass first, then create the player and load.
            if (token == null) token = bypass.acquire(BypassReason.RESULTS_AB)
            val h = handle ?: backend.createPlayer().also { created ->
                handle = created
                created.playing?.let { flow ->
                    playingJob = viewModelScope.launch { flow.collect { v -> _ab.update { it.copy(playing = v) } } }
                }
            }
            h.player.load(source.source, source.loop)
            plan = p
            _ab.update { it.copy(status = AbStatus.READY) }
            applyCurrent()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (state.sourceKind == AbSourceKind.USER_FILE) {
                // Unreadable / unsupported file: say so and fall back to the demo clip.
                fileUri = null
                _ab.update { it.copy(sourceKind = AbSourceKind.DEMO_CLIP, fileName = null, fileError = true) }
                prepare()
            } else {
                _ab.update { it.copy(status = AbStatus.ERROR, playing = false) }
            }
        }
    }

    /** Swap to the option currently selected on the toggle (20 ms duck inside the player, same playhead). */
    private fun applyCurrent() {
        val p = plan ?: return
        val h = handle ?: return
        if (_ab.value.personalized) h.player.swapTo(p.personalizedCfg, p.personalizedMatchGainDb)
        else h.player.swapTo(p.originalCfg, p.originalMatchGainDb)
    }

    override fun onCleared() {
        stopAb()
    }
}
