package my.umarh.paudio.audio.effect

import android.content.Context
import android.media.AudioManager
import my.umarh.paudio.core.dsp.DpEqPlan
import my.umarh.paudio.core.dsp.DpLadderStep
import my.umarh.paudio.core.model.EqConfig
import my.umarh.paudio.core.model.OutputDevice

/** System-wide engine state (SPEC §4 item 8, F21). */
sealed interface EngineState {
    /** Engine not running. */
    data object Off : EngineState

    /** Running but the session source isn't collecting yet. */
    data object Starting : EngineState

    /** Session mode, no player has announced a session. */
    data object Waiting : EngineState

    /** Session mode with live sessions; [apps] are the announcing package names (may repeat for several sessions). */
    data class Active(val apps: List<String>) : EngineState

    /** Legacy (global session 0) mode; [active] = the effect is currently enabled (Personalized). */
    data class Legacy(val active: Boolean) : EngineState

    /** At least one bypass token is open (P0: a test or A/B is playing); effects are disabled. */
    data object Bypassed : EngineState

    /** Another equalizer app took control (or refused creation) — copy "Another equalizer app is controlling...". */
    data object Conflict : EngineState

    /** No profile/target for the current headset. */
    data class NoProfile(val device: OutputDevice) : EngineState

    /** Speaker / earpiece / HDMI / unknown route: never processed. */
    data object SpeakerOutput : EngineState

    data class Error(val cause: EngineError) : EngineState
}

enum class EngineError {
    /** "This device's audio system refused PAudio's effect. Try Legacy mode in Settings." */
    CREATION_FAILED,

    /** The session receiver could not be registered / failed. */
    SOURCE_FAILED,
}

/**
 * @property ladder current fallback-ladder step; when it changes the caller must re-plan its [EqConfig]
 *   (`DpEqPlanner.plan(..., ladder = ladder)`) and call [EffectEngine.setTarget] again.
 * @property reducedPrecision true at [DpLadderStep.SPEC40].
 */
data class EngineDiagnostics(
    val ladder: DpLadderStep = DpLadderStep.MAIN,
    val bandCount: Int = 0,
    val liveSessions: Int = 0,
    val sessionsIgnoredOverCap: Int = 0,
    val sessionsReleasedIdle: Int = 0,
) {
    val reducedPrecision: Boolean get() = ladder == DpLadderStep.SPEC40
}

/** `AudioManager.isMusicActive` seam (the "no session seen for 60 s while music plays" hint). */
fun interface MusicActivity {
    fun isMusicActive(): Boolean
}

class AudioManagerMusicActivity(context: Context) : MusicActivity {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun isMusicActive(): Boolean = audioManager.isMusicActive
}

/** Builds the backend-neutral config from a `:core:dsp` plan (never build cutoffs from the spec formula). */
fun DpEqPlan.toEqConfig(limiterEnabled: Boolean = true): EqConfig = EqConfig(
    preampDb = preampDb,
    bandCutoffsHz = cutoffHz.toList(),
    channelGainsDb = listOf(gainsLeft.toList(), gainsRight.toList()),
    limiterEnabled = limiterEnabled,
)
