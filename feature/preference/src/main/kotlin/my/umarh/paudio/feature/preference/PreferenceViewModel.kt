package my.umarh.paudio.feature.preference

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.umarh.paudio.audio.playback.DemoClipSource
import my.umarh.paudio.core.model.BypassController
import my.umarh.paudio.core.model.BypassReason
import my.umarh.paudio.core.model.BypassToken
import my.umarh.paudio.core.preference.AbRoundGate
import my.umarh.paudio.core.preference.AbSide
import my.umarh.paudio.core.preference.DemoClip
import my.umarh.paudio.core.preference.PreferenceAxis
import my.umarh.paudio.core.preference.PreferenceBisection
import my.umarh.paudio.data.repo.ProfileSettings

enum class PrefPhase { LOADING, UNAVAILABLE, INTRO, ROUND, RESULT, FINE_TUNE }

enum class AudioStatus { IDLE, PREPARING, READY, ERROR }

/**
 * UI state of the whole flow. [gLeft]/[gRight] (the total curve G per ear) and the gain fields are only meaningful in
 * [PrefPhase.RESULT] / [PrefPhase.FINE_TUNE]; the rounds are blind and never show a curve.
 */
@Immutable
data class PreferenceUiState(
    val phase: PrefPhase = PrefPhase.LOADING,
    val hasHearingTest: Boolean = false,
    /** 1-based number of the comparison on screen. */
    val roundNumber: Int = 0,
    val maxRounds: Int = PreferenceBisection.TOTAL_SLOTS,
    val axis: PreferenceAxis? = null,
    /** The option currently audible (A or B). */
    val activeSide: AbSide = AbSide.A,
    val aHeard: Boolean = false,
    val bHeard: Boolean = false,
    /** True once A and B have each played at least [AbRoundGate.MIN_LISTEN_SECONDS] s. */
    val canChoose: Boolean = false,
    val clip: DemoClip = DemoClip.DISCIPLINE,
    val audio: AudioStatus = AudioStatus.IDLE,
    val playing: Boolean = false,
    val strength: Float = 0.5f,
    val gB: Float = 0f,
    val gP: Float = 0f,
    val gT: Float = 0f,
    /** Fine-tune Original/Personalized toggle. */
    val personalized: Boolean = true,
    val gLeft: DoubleArray? = null,
    val gRight: DoubleArray? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveError: Boolean = false,
)

/**
 * A/B preference test + fine-tune (F14, F15).
 *
 * Rounds come from [PreferenceBisection] (B,P,T x3, "Can't tell" ends an axis). Every round plays one clip as a 12 s
 * loop; A and B are the c-s / c+s candidates on the round's axis on top of the hearing-compensation baseline and the
 * current centres of the other two axes, loudness-matched by `AbGainPlanner` over BOTH options.
 *
 * P0 (SPEC 2.2 #1): the audio (player + the ViewModel's own `PREFERENCE_AB` bypass token, in addition to the one
 * `ExoAbPlayer.load()` takes) lives only while the screen is STARTED ([startAudio] / [stopAudio], driven by the UI's
 * lifecycle), so a backgrounded screen or one on the back stack never leaves the system-wide engine bypassed.
 * Everything runs on the main thread (ExoPlayer and the engine's `acquire`).
 */
@HiltViewModel
class PreferenceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val data: PreferenceData,
    private val backend: PreferenceAudioBackend,
    private val bypass: BypassController,
) : ViewModel() {
    private val requestedProfileId: Long? = savedStateHandle.toRoute<PreferenceRoute>().profileId

    private val _ui = MutableStateFlow(PreferenceUiState())
    val ui: StateFlow<PreferenceUiState> = _ui

    private var baseline: PreferenceBaseline? = null
    private var bisection: PreferenceBisection = PreferenceBisection(data.random())
    private val gate = AbRoundGate()
    private val records = mutableListOf<RoundRecord>()
    private var startedAt = 0L

    private var clipOverride: DemoClip? = null
    private var plan: PrefPlan? = null
    private var handle: PrefAudio? = null
    private var loadedClip: DemoClip? = null
    private var token: BypassToken? = null
    private var prepareJob: Job? = null
    private var playingJob: Job? = null
    private var ticksJob: Job? = null
    private var audioWanted = false

    /** False from the start of a round until its plan is swapped in: listen ticks of the old audio/side are dropped. */
    private var roundApplied = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val b = data.load(requestedProfileId)
            if (b == null) {
                _ui.value = PreferenceUiState(phase = PrefPhase.UNAVAILABLE)
                return
            }
            baseline = b
            _ui.value = PreferenceUiState(phase = PrefPhase.INTRO, hasHearingTest = b.hasTest, strength = b.strength.toFloat())
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            _ui.value = PreferenceUiState(phase = PrefPhase.UNAVAILABLE)
        }
    }

    // ---- flow --------------------------------------------------------------------------------------------------------

    /** Intro -> first round (also restarts a finished test from the beginning). */
    fun begin() {
        if (baseline == null) return
        releaseAudio()
        bisection = PreferenceBisection(data.random())
        records.clear()
        clipOverride = null
        startedAt = data.nowMs()
        beginRound()
    }

    private fun currentClip(axis: PreferenceAxis): DemoClip = clipOverride ?: DemoClip.defaultFor(axis)

    private fun beginRound() {
        val r = bisection.currentRound
        if (r == null) {
            finishRounds()
            return
        }
        gate.reset()
        roundApplied = false
        _ui.update {
            it.copy(
                phase = PrefPhase.ROUND,
                roundNumber = bisection.roundsCompleted + 1,
                axis = r.axis,
                activeSide = AbSide.A,
                aHeard = false,
                bHeard = false,
                canChoose = false,
                clip = currentClip(r.axis),
            )
        }
        launchPrepare()
    }

    /** "Choose A" / "Choose B"; ignored until the gate has opened (A and B each played >= 2 s). */
    fun choose(side: AbSide, roundNumber: Int = bisection.roundsCompleted + 1) {
        val r = bisection.currentRound ?: return
        if (roundNumber != bisection.roundsCompleted + 1) return // stale tap (double tap) for an answered round
        if (_ui.value.phase != PrefPhase.ROUND || !gate.canChoose) return
        records += RoundRecord.of(r, side, _ui.value.clip.name)
        bisection.choose(side)
        beginRound()
    }

    fun cantTell(roundNumber: Int = bisection.roundsCompleted + 1) {
        val r = bisection.currentRound ?: return
        if (roundNumber != bisection.roundsCompleted + 1) return // stale tap (double tap) for an answered round
        if (_ui.value.phase != PrefPhase.ROUND) return
        records += RoundRecord.of(r, null, _ui.value.clip.name)
        bisection.cantTell()
        beginRound()
    }

    /** "Try another song": cycles the clip, keeps the round (and what was already heard) and the play state. */
    fun tryAnotherSong() {
        val st = _ui.value
        if (st.phase != PrefPhase.ROUND) return
        clipOverride = st.clip.next()
        _ui.update { it.copy(clip = clipOverride!!) }
        launchPrepare()
    }

    /** Fine-tune clip picker. */
    fun setClip(clip: DemoClip) {
        if (_ui.value.phase != PrefPhase.FINE_TUNE) return
        clipOverride = clip
        _ui.update { it.copy(clip = clip) }
        launchPrepare()
    }

    private fun finishRounds() {
        val b = baseline ?: return
        val o = bisection.outcome
        releaseAudio()
        _ui.update {
            it.copy(
                phase = PrefPhase.RESULT,
                axis = null,
                strength = b.strength.toFloat(),
                gB = o.gB.toFloat(),
                gP = o.gP.toFloat(),
                gT = o.gT.toFloat(),
                personalized = true,
                playing = false,
            )
        }
        refreshCurve()
    }

    fun openFineTune() {
        if (_ui.value.phase != PrefPhase.RESULT) return
        _ui.update { it.copy(phase = PrefPhase.FINE_TUNE, clip = clipOverride ?: it.clip, personalized = true) }
        launchPrepare()
    }

    // ---- fine-tune ---------------------------------------------------------------------------------------------------

    fun setStrength(v: Float) = fineTune { it.copy(strength = v.coerceIn(0f, 1f)) }

    fun setBass(v: Float) = fineTune { it.copy(gB = quantize(v, FineTuneRange.of(PreferenceAxis.BASS))) }

    fun setPresence(v: Float) = fineTune { it.copy(gP = quantize(v, FineTuneRange.of(PreferenceAxis.PRESENCE))) }

    fun setTreble(v: Float) = fineTune { it.copy(gT = quantize(v, FineTuneRange.of(PreferenceAxis.TREBLE))) }

    private fun quantize(v: Float, r: ClosedFloatingPointRange<Double>): Float =
        ((v * 2f).roundToInt() / 2f).coerceIn(r.start.toFloat(), r.endInclusive.toFloat())

    private fun fineTune(change: (PreferenceUiState) -> PreferenceUiState) {
        val st = _ui.value
        if (st.phase != PrefPhase.RESULT && st.phase != PrefPhase.FINE_TUNE) return
        _ui.update(change)
        refreshCurve()
        if (st.phase == PrefPhase.FINE_TUNE) launchPrepare()
    }

    fun setPersonalized(on: Boolean) {
        _ui.update { it.copy(personalized = on) }
        if (_ui.value.audio == AudioStatus.READY && _ui.value.phase == PrefPhase.FINE_TUNE) applyCurrent()
    }

    private fun refreshCurve() {
        val b = baseline ?: return
        val st = _ui.value
        val c = PreferencePlanner.curve(b, st.strength.toDouble(), st.gB.toDouble(), st.gP.toDouble(), st.gT.toDouble())
        _ui.update { it.copy(gLeft = c.gLeft, gRight = c.gRight) }
    }

    // ---- save --------------------------------------------------------------------------------------------------------

    /** Persists the PreferenceTest (the bisection result + rounds) and the profile's strength / gB / gP / gT. */
    fun save() {
        val b = baseline ?: return
        val st = _ui.value
        if ((st.phase != PrefPhase.RESULT && st.phase != PrefPhase.FINE_TUNE) || st.saving || st.saved) return
        val o = bisection.outcome
        _ui.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            try {
                data.save(
                    profileId = b.profileId,
                    startedAt = startedAt,
                    test = Triple(o.gB.toFloat(), o.gP.toFloat(), o.gT.toFloat()),
                    roundsJson = Json.encodeToString(records.toList()),
                    settings = ProfileSettings(st.strength, st.gB, st.gP, st.gT, b.personalizationOn),
                )
                releaseAudio()
                _ui.update { it.copy(saving = false, saved = true) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _ui.update { it.copy(saving = false, saveError = true) }
            }
        }
    }

    // ---- audio -------------------------------------------------------------------------------------------------------

    /** Call when the screen becomes STARTED. Idempotent. Does not start playback. */
    fun startAudio() {
        audioWanted = true
        launchPrepare()
    }

    /** Call when the screen stops or leaves the composition: pauses, releases the player, closes the bypass token. */
    fun stopAudio() {
        audioWanted = false
        releaseAudio()
    }

    private fun releaseAudio() {
        prepareJob?.cancel()
        prepareJob = null
        playingJob?.cancel()
        playingJob = null
        ticksJob?.cancel()
        ticksJob = null
        val h = handle
        handle = null
        plan = null
        loadedClip = null
        roundApplied = false
        if (h != null) {
            runCatching { h.player.pause() }
            runCatching { h.player.release() }
        }
        val t = token
        token = null
        runCatching { t?.close() }
        _ui.update { it.copy(audio = AudioStatus.IDLE, playing = false) }
    }

    fun retryAudio() = launchPrepare()

    fun togglePlay() {
        val h = handle ?: return
        if (_ui.value.audio != AudioStatus.READY) return
        if (_ui.value.playing) {
            h.player.pause()
            if (h.playing == null) _ui.update { it.copy(playing = false) }
        } else {
            h.player.play()
            if (h.playing == null) _ui.update { it.copy(playing = true) }
        }
    }

    /** Listen to [side] (instant swap, 20 ms duck in the player); starts playback if paused. */
    fun listen(side: AbSide) {
        if (_ui.value.phase != PrefPhase.ROUND) return
        _ui.update { it.copy(activeSide = side) }
        if (_ui.value.audio != AudioStatus.READY) return
        applyCurrent()
        if (!_ui.value.playing) togglePlay()
    }

    fun pause() {
        val h = handle ?: return
        h.player.pause()
        if (h.playing == null) _ui.update { it.copy(playing = false) }
    }

    private fun launchPrepare() {
        if (!audioWanted || _ui.value.saved) return
        val phase = _ui.value.phase
        if (phase != PrefPhase.ROUND && phase != PrefPhase.FINE_TUNE) return
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        if (_ui.value.phase == PrefPhase.FINE_TUNE) delay(SLIDER_DEBOUNCE_MS) // coalesce slider drags
        val b = baseline ?: return
        val st = _ui.value
        val clip = st.clip
        val reload = handle == null || loadedClip != clip
        if (reload) _ui.update { it.copy(audio = AudioStatus.PREPARING) }
        try {
            val predictor = backend.predictor(clip)
            val fs = backend.outputSampleRate()
            val p = when (st.phase) {
                PrefPhase.ROUND -> {
                    val r = bisection.currentRound ?: return
                    val o = bisection.outcome
                    val (ca, cb) = PreferencePlanner.roundCurves(b, doubleArrayOf(o.gB, o.gP, o.gT), r)
                    PreferencePlanner.roundPlan(ca, cb, predictor, fs)
                }
                PrefPhase.FINE_TUNE -> {
                    val c = PreferencePlanner.curve(b, st.strength.toDouble(), st.gB.toDouble(), st.gP.toDouble(), st.gT.toDouble())
                    PreferencePlanner.fineTunePlan(c, predictor, fs)
                }
                else -> return
            }
            // P0: bypass first, then create the player and load.
            if (token == null) token = bypass.acquire(BypassReason.PREFERENCE_AB)
            val h = handle ?: backend.createPlayer().also { created ->
                handle = created
                created.playing?.let { flow ->
                    playingJob = viewModelScope.launch { flow.collect { v -> _ui.update { it.copy(playing = v) } } }
                }
                created.ticks?.let { flow ->
                    ticksJob = viewModelScope.launch { flow.collect { t -> onTick(t.side, t.deltaMs) } }
                }
            }
            if (reload) {
                val wasPlaying = _ui.value.playing
                h.player.pause()
                if (h.playing == null) _ui.update { it.copy(playing = false) }
                val src = DemoClipSource.preference(clip)
                h.player.load(src.source, src.loop)
                loadedClip = clip
                plan = p
                _ui.update { it.copy(audio = AudioStatus.READY) }
                applyCurrent()
                if (wasPlaying) {
                    h.player.play()
                    if (h.playing == null) _ui.update { it.copy(playing = true) }
                }
            } else {
                plan = p
                applyCurrent()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // P0: a failed create/load must not leave the token or a half-loaded player behind; retry re-acquires.
            releaseAudio()
            _ui.update { it.copy(audio = AudioStatus.ERROR, playing = false) }
        }
    }

    /** Swap to the option currently selected (A/B in a round, Original/Personalized in the fine-tune). */
    private fun applyCurrent() {
        val p = plan ?: return
        val h = handle ?: return
        val st = _ui.value
        if (st.phase == PrefPhase.ROUND) {
            val i = if (st.activeSide == AbSide.A) 0 else 1
            h.swap(p.configs[i], p.matchGainDb[i], st.activeSide)
            roundApplied = true
        } else {
            val i = if (st.personalized) 1 else 0
            h.swap(p.configs[i], p.matchGainDb[i], null)
        }
    }

    private fun onTick(side: AbSide, deltaMs: Long) {
        if (_ui.value.phase != PrefPhase.ROUND || !roundApplied) return
        gate.onPlayed(side, deltaMs / 1000.0)
        val played = gate.playedSeconds
        _ui.update {
            it.copy(
                aHeard = played.getValue(AbSide.A) >= AbRoundGate.MIN_LISTEN_SECONDS,
                bHeard = played.getValue(AbSide.B) >= AbRoundGate.MIN_LISTEN_SECONDS,
                canChoose = gate.canChoose,
            )
        }
    }

    override fun onCleared() {
        releaseAudio()
    }

    private companion object {
        const val SLIDER_DEBOUNCE_MS = 150L
    }
}
