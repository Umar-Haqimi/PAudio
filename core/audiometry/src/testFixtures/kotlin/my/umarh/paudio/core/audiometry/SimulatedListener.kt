package my.umarh.paudio.core.audiometry

import my.umarh.paudio.core.model.Ear
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Answers Quick-test trials. */
fun interface QuickResponder {
    fun respond(trial: Trial): Boolean
}

/**
 * Psychometric simulated listener (SPEC §7.2):
 * `p(yes | L) = γ + (1 − γ − λ) / (1 + e^{−(L − T)/σ})` with γ = λ = 0.02, σ = 2 dB and a true threshold `T` per
 * (ear, f). Catch (silent) trials are answered "yes" with probability γ. Seeded and deterministic.
 *
 * Thorough model ([thoroughConfirmLevel]): the listener confirms when the perceived p crosses 0.5, with
 * Gaussian noise (σ = 1.5 dB) on the crossing level.
 */
class SimulatedListener(
    private val thresholds: Map<PointKey, Double>,
    private val rng: Random,
    val gamma: Double = 0.02,
    val lambda: Double = 0.02,
    val sigma: Double = 2.0,
    val thoroughNoiseSd: Double = 1.5,
) : QuickResponder {

    fun trueThreshold(ear: Ear, freqHz: Int): Double =
        requireNotNull(thresholds[PointKey(ear, freqHz)]) { "no true threshold for $ear @ $freqHz" }

    fun pYes(ear: Ear, freqHz: Int, levelDbfs: Double): Double {
        val t = trueThreshold(ear, freqHz)
        return gamma + (1.0 - gamma - lambda) / (1.0 + exp(-(levelDbfs - t) / sigma))
    }

    override fun respond(trial: Trial): Boolean =
        if (trial.isCatch) rng.nextDouble() < gamma else rng.nextDouble() < pYes(trial.ear, trial.freqHz, checkNotNull(trial.levelDbfs))

    /** Level at which p(yes) = 0.5 for the true threshold, plus N(0, [thoroughNoiseSd]) noise. */
    fun thoroughConfirmLevel(ear: Ear, freqHz: Int): Double {
        val q = (0.5 - gamma) / (1.0 - gamma - lambda)
        val crossing = trueThreshold(ear, freqHz) + sigma * ln(q / (1.0 - q))
        return crossing + thoroughNoiseSd * gaussian()
    }

    /**
     * Answer one Thorough dial run: returns the dial position at which the listener confirms, or null if they
     * hit a limit (the caller then uses [ThoroughTestEngine.reportLimit] at [limitPosition]).
     */
    fun answerRun(run: DialRun): RunAnswer {
        val target = thoroughConfirmLevel(run.ear, run.freqHz)
        return when {
            target > run.lMax -> RunAnswer.Limit(run.maxPosition)
            target < run.lMin -> RunAnswer.Limit(run.minPosition)
            else -> RunAnswer.Confirm(run.positionOffset + (target - run.lMin).roundToInt())
        }
    }

    sealed interface RunAnswer {
        data class Confirm(val position: Int) : RunAnswer
        data class Limit(val position: Int) : RunAnswer
    }

    private fun gaussian(): Double {
        val u1 = max(rng.nextDouble(), 1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}

/** A listener that answers "yes" to everything (including catch trials). */
object AlwaysYesListener : QuickResponder {
    override fun respond(trial: Trial): Boolean = true
}

/** Convenience: drive a [QuickTestEngine] to completion with [responder]; returns the number of trials presented. */
fun QuickTestEngine.runToCompletion(responder: QuickResponder): Int {
    var n = 0
    while (true) {
        val t = next() ?: break
        submit(responder.respond(t))
        n++
    }
    return n
}

/** Convenience: drive a [ThoroughTestEngine] to completion with a [SimulatedListener]; returns the run count. */
fun ThoroughTestEngine.runToCompletion(listener: SimulatedListener): Int {
    var n = 0
    while (true) {
        val run = nextRun() ?: break
        when (val a = listener.answerRun(run)) {
            is SimulatedListener.RunAnswer.Confirm -> confirm(a.position)
            is SimulatedListener.RunAnswer.Limit -> reportLimit(a.position)
        }
        n++
    }
    return n
}
