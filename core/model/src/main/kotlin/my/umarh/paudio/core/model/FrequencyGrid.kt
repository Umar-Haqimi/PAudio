package my.umarh.paudio.core.model

/** The hearing-test frequency grid (SPEC §2.1.3). */
object FrequencyGrid {
    val TEST_FREQUENCIES_HZ: List<Int> =
        listOf(1000, 2000, 3000, 4000, 6000, 8000, 10000, 12500, 14000)

    /** Frequencies at/above this use ±5 % / 8 Hz warble tones; below use pure tones (SPEC §2.1.2). */
    const val WARBLE_FROM_HZ: Int = 4000
}
