package my.umarh.paudio.core.preference

/** Which side of an A/B comparison. */
enum class AbSide { A, B }

/**
 * Gate for the "Choose" buttons: enabled only after BOTH A and B have each accumulated at least
 * [MIN_LISTEN_SECONDS] of playback (SPEC §4 flow 5). The UI feeds playback time via [onPlayed].
 */
class AbRoundGate {
    private var a = 0.0
    private var b = 0.0

    val playedSeconds: Map<AbSide, Double> get() = mapOf(AbSide.A to a, AbSide.B to b)

    val canChoose: Boolean get() = a >= MIN_LISTEN_SECONDS && b >= MIN_LISTEN_SECONDS

    fun onPlayed(side: AbSide, durationSeconds: Double) {
        require(durationSeconds >= 0.0) { "negative duration" }
        when (side) {
            AbSide.A -> a += durationSeconds
            AbSide.B -> b += durationSeconds
        }
    }

    /** Call at the start of every new round. */
    fun reset() {
        a = 0.0
        b = 0.0
    }

    companion object {
        const val MIN_LISTEN_SECONDS = 2.0

        /** Each round plays a 12 s loop. */
        const val LOOP_SECONDS = 12
    }
}
