package my.umarh.paudio.core.preference

/** The three bundled CC BY-NC-SA demo clips (SPEC §2.1.6). Assets live in `:audio:playback`. */
enum class DemoClip(val assetPath: String, val ltasAssetPath: String) {
    DISCIPLINE("demo/discipline.flac", "demo/discipline.ltas.json"),
    ECHOPLEX("demo/echoplex.flac", "demo/echoplex.ltas.json"),
    GHOSTS_34("demo/ghosts34.flac", "demo/ghosts34.ltas.json");

    /** Next clip for "Try another song": cycles DISCIPLINE -> ECHOPLEX -> GHOSTS_34 -> DISCIPLINE. */
    fun next(): DemoClip = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Reference pink-noise LTAS (equal power per 1/3-octave band), used for user files. */
        const val PINK_LTAS_ASSET_PATH = "demo/pink.ltas.json"

        /** Default clip per axis: bass -> Echoplex, presence -> Discipline, treble -> 34 Ghosts IV. */
        fun defaultFor(axis: PreferenceAxis): DemoClip = when (axis) {
            PreferenceAxis.BASS -> ECHOPLEX
            PreferenceAxis.PRESENCE -> DISCIPLINE
            PreferenceAxis.TREBLE -> GHOSTS_34
        }
    }
}
