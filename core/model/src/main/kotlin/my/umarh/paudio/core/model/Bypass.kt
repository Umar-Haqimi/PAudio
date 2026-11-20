package my.umarh.paudio.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * P0 self-colouring guard (SPEC §2.2 #1, §5.3).
 *
 * While PAudio itself plays audio (calibration, hearing test tones, results A/B, preference A/B),
 * every system-wide / foreign-session effect owned by the engine must be disabled, or the
 * measurement and comparisons would include PAudio's own EQ. Callers hold a [BypassToken] for the
 * whole duration and close it in `finally`.
 *
 * Bypass is reference-counted: the engine is bypassed while at least one token is open, so nested
 * callers (e.g. a test screen that also plays an A/B clip) can't un-bypass prematurely.
 * Implemented by `:audio:effect`'s engine; a fake lives in `:testing`.
 */
interface BypassController {
    /** Opens a token and disables all engine-owned effects before returning. [reason] is for diagnostics only. */
    fun acquire(reason: BypassReason): BypassToken

    /** True while at least one token is open. */
    val bypassed: StateFlow<Boolean>
}

/** An open bypass request. [close] is idempotent; closing the last token restores the previous engine state. */
interface BypassToken : AutoCloseable {
    val reason: BypassReason
    override fun close()
}

enum class BypassReason { CALIBRATION, HEARING_TEST, RESULTS_AB, PREFERENCE_AB, OTHER }

/** Runs [block] with the engine bypassed, always releasing the token (also on cancellation). */
inline fun <T> BypassController.withBypass(reason: BypassReason, block: () -> T): T =
    acquire(reason).use { block() }
