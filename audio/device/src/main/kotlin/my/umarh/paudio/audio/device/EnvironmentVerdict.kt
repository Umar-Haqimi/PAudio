package my.umarh.paudio.audio.device

import my.umarh.paudio.core.model.EnvSnapshot
import kotlin.math.abs

enum class EnvWarning { MONO_ON, BALANCE_OFF_CENTER, SPATIALIZER_ON }

enum class EnvCheck { MONO, BALANCE, SPATIALIZER, DND }

/**
 * Interpretation of an [EnvSnapshot] (SPEC §2.2 trap 11). [blocking] warnings make per-ear results meaningless,
 * so a test must not start; [unknown] checks that could not be read are a non-blocking caution; DND is
 * informational only.
 */
data class EnvironmentVerdict(
    val blocking: List<EnvWarning>,
    val unknown: List<EnvCheck>,
    val dndOn: Boolean,
) {
    val isBlocking: Boolean get() = blocking.isNotEmpty()

    companion object {
        /** Balance is considered off-centre beyond this magnitude (the slider's own notch is ~0.01). */
        const val BALANCE_TOLERANCE = 0.05f

        fun of(s: EnvSnapshot): EnvironmentVerdict {
            val blocking = buildList {
                if (s.mono == true) add(EnvWarning.MONO_ON)
                if (s.balance?.let { abs(it) > BALANCE_TOLERANCE } == true) add(EnvWarning.BALANCE_OFF_CENTER)
                if (s.spatializerOn == true) add(EnvWarning.SPATIALIZER_ON)
            }
            val unknown = buildList {
                if (s.mono == null) add(EnvCheck.MONO)
                if (s.balance == null) add(EnvCheck.BALANCE)
                if (s.spatializerOn == null) add(EnvCheck.SPATIALIZER)
                if (s.dndOn == null) add(EnvCheck.DND)
            }
            return EnvironmentVerdict(blocking, unknown, s.dndOn == true)
        }
    }
}
