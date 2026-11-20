package my.umarh.paudio.core.preference

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Preference axes with their bisection parameters (SPEC §4 flow 5). Gains in dB. */
enum class PreferenceAxis(val initialStep: Double, val minGain: Double, val maxGain: Double) {
    /** Low shelf at 105 Hz. */
    BASS(6.0, -6.0, 12.0),

    /** Peak at 3 kHz. */
    PRESENCE(4.0, -6.0, 6.0),

    /** High shelf at 8 kHz. */
    TREBLE(5.0, -8.0, 8.0),
}

/** User's answer for one round. */
enum class RoundChoice { A, B, CANT_TELL }

/** One presented comparison. [plusSide] is the side playing the larger of the two gains. */
data class BisectionRound(
    val index: Int,
    val axis: PreferenceAxis,
    val aGain: Double,
    val bGain: Double,
    val plusSide: AbSide,
)

/** Serializable log entry for `PreferenceTest.roundsJson`. */
@Serializable
data class RoundLog(
    val round: Int,
    val axis: String,
    val aGain: Double,
    val bGain: Double,
    val choice: String,
    val plusSide: String,
)

data class PreferenceOutcome(val gB: Double, val gP: Double, val gT: Double, val rounds: List<RoundLog>) {
    fun roundsJson(): String = Json.encodeToString(rounds)
}

/**
 * Deterministic bisection state machine (SPEC §4 flow 5).
 *
 * Rounds interleave axes B, P, T, B, P, T, B, P, T (9 slots). Per axis: centre c = 0, step s = s0.
 * Each round compares `lo = clamp(c - s)` against `hi = clamp(c + s)` with A/B sides randomized by
 * [random]. Picking a side sets c to that side's (clamped) value and halves s. "Can't tell" ends the
 * axis: its remaining slots are skipped and c stays.
 *
 * Clamping rule: each compared value is clamped independently to the axis range
 * `[minGain, maxGain]` (e.g. presence at c = 6, s = 1 compares 5 vs clamp(7) = 6), so c always stays in
 * range. If both values collapse to the same number (`lo == hi`, impossible with the defined
 * parameters because c is in range and s > 0) the axis is auto-ended without showing a round.
 *
 * Usage: read [currentRound]; call [choose] or [cantTell]; repeat until [isFinished]; read [outcome].
 */
class PreferenceBisection(private val random: Random = Random.Default) {
    private val center = DoubleArray(3)
    private val step = DoubleArray(3) { PreferenceAxis.entries[it].initialStep }
    private val ended = BooleanArray(3)
    private val log = mutableListOf<RoundLog>()
    private var slot = 0

    var currentRound: BisectionRound? = null
        private set

    val isFinished: Boolean get() = currentRound == null

    /** Number of rounds answered so far. */
    val roundsCompleted: Int get() = log.size

    init {
        advance()
    }

    /** Pick the side the user preferred. */
    fun choose(side: AbSide) {
        val r = checkNotNull(currentRound) { "bisection finished" }
        val picked = if (side == AbSide.A) r.aGain else r.bGain
        val i = r.axis.ordinal
        center[i] = picked
        step[i] = step[i] / 2.0
        record(r, if (side == AbSide.A) RoundChoice.A else RoundChoice.B)
    }

    fun cantTell() {
        val r = checkNotNull(currentRound) { "bisection finished" }
        ended[r.axis.ordinal] = true
        record(r, RoundChoice.CANT_TELL)
    }

    /** Current best estimate (final once [isFinished]). */
    val outcome: PreferenceOutcome
        get() = PreferenceOutcome(center[0], center[1], center[2], log.toList())

    private fun record(r: BisectionRound, choice: RoundChoice) {
        log += RoundLog(r.index, r.axis.name, r.aGain, r.bGain, choice.name, r.plusSide.name)
        slot++
        advance()
    }

    private fun advance() {
        currentRound = null
        while (slot < TOTAL_SLOTS) {
            val axis = PreferenceAxis.entries[slot % 3]
            val i = axis.ordinal
            if (ended[i]) { slot++; continue }
            val lo = (center[i] - step[i]).coerceIn(axis.minGain, axis.maxGain)
            val hi = (center[i] + step[i]).coerceIn(axis.minGain, axis.maxGain)
            if (lo == hi) { ended[i] = true; slot++; continue }
            val plusOnA = random.nextBoolean()
            currentRound = if (plusOnA) {
                BisectionRound(log.size, axis, hi, lo, AbSide.A)
            } else {
                BisectionRound(log.size, axis, lo, hi, AbSide.B)
            }
            return
        }
    }

    companion object {
        const val TOTAL_SLOTS = 9
    }
}
