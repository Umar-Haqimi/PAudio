package my.umarh.paudio.feature.hearingtest

import my.umarh.paudio.core.audiometry.RunKind
import my.umarh.paudio.core.model.TestMode

/**
 * Why the test is paused (SPEC §4 item 8 copy table). Declaration order is the display priority when several
 * apply at once.
 */
enum class PauseReason {
    HEADSET_DISCONNECTED,
    FOCUS_LOST,
    VOLUME_CHANGED,
    BACKGROUNDED,

    /** Quick only: "Some answers looked like guesses. Want to redo the test?" */
    UNRELIABLE,
}

enum class AbortReason {
    /** Calibration ramp reached -10 dBFS without a tap. */
    CANT_HEAR,

    /** Calibration could not land in [-75, -35] dBFS. */
    VOLUME_RANGE,

    /** The AudioTrack could not be opened / kept alive. */
    AUDIO_ERROR,

    /** Paused for longer than the pause timeout (the engine bypass must not be held forever). */
    PAUSE_TIMEOUT,

    SAVE_FAILED,

    /** No processable output (speaker, earpiece, HDMI, nothing) when the session started. */
    NO_HEADPHONES,

    /** The user left the test (back / exit dialog) or the screen was torn down. */
    USER,
}

enum class ChannelCheckProblem { NONE, WRONG_EAR, TOO_QUIET }

enum class ChannelAnswer { LEFT, RIGHT, BOTH, LOUDER }

enum class VolumeDirection { UP, DOWN }

enum class CalibrationPhase {
    /** Starting the volume / waiting for the first tone. */
    PREPARING,

    /** The ramp is playing; the user taps when they hear it. */
    RAMP,

    /** `setStreamVolume` was refused: the user must use the volume keys, then continue. */
    MANUAL_VOLUME,
}

/** What the level dial shows. No dB numbers anywhere (SPEC §4 Thorough detail). */
data class DialUi(
    val minPosition: Int,
    val maxPosition: Int,
    val position: Int,
    val kind: RunKind,
    /** The dial is at the loudest detent ("I can't hear it even at the top" becomes available). */
    val atTop: Boolean,
    /** The dial is at the quietest detent ("I still hear it at the lowest level" becomes available). */
    val atBottom: Boolean,
)

/** State machine of SPEC §4 item 8: `Idle → Prep → ChannelCheck → Calibrating → Testing ⇄ Paused → Results | Aborted`. */
sealed interface SessionState {
    /** Acquiring the bypass token, opening the stream. */
    data object Starting : SessionState

    data class ChannelCheck(val step: Int, val steps: Int, val problem: ChannelCheckProblem) : SessionState

    data class Calibrating(
        val phase: CalibrationPhase,
        val rerun: Int,
        val canTap: Boolean,
        val direction: VolumeDirection? = null,
        /** The user's volume could not be set automatically at the start. */
        val volumeRefused: Boolean = false,
    ) : SessionState

    data class Testing(
        val mode: TestMode,
        val progress: Float,
        val completedPoints: Int,
        val totalPoints: Int,
        /** Frequency of the current/last presented stimulus (drives the wave progress; 1 kHz before the first). */
        val frequencyHz: Int,
        /** Quick: the answer buttons are live (a stimulus is being presented / awaiting the answer). */
        val trialOpen: Boolean,
        val dial: DialUi?,
        /** Calibration landed outside the ideal window ([-68, -50]) but inside [-75, -35]. */
        val calibrationWarning: Boolean,
    ) : SessionState

    data class Paused(
        val reason: PauseReason,
        val all: Set<PauseReason>,
        /** The Resume action is available (headset back, app in the foreground). */
        val canResume: Boolean,
        /** Resume was tried but the volume could not be restored: ask for the volume keys. */
        val volumeKeysNeeded: Boolean,
        val calibrating: Boolean,
        /** UNRELIABLE at the end of the test (Keep = save as unreliable) vs mid-test (Continue). */
        val testFinished: Boolean = false,
    ) : SessionState

    data object Saving : SessionState

    /** Terminal; emitted only after the stream is closed, the volume restored and the bypass token released. */
    data class Finished(val testId: Long) : SessionState

    /** Terminal; emitted only after cleanup, like [Finished]. */
    data class Aborted(val reason: AbortReason) : SessionState
}

/** One-off signals for haptics / accessibility, emitted only AFTER the stimulus has stopped (SPEC §2.2 #20). */
sealed interface SessionSignal {
    data class Responded(val heard: Boolean) : SessionSignal

    /** A Thorough run was confirmed. */
    data object RunConfirmed : SessionSignal
}
