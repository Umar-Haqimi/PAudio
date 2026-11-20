package my.umarh.paudio.core.model

/**
 * Accessibility/audio settings that make per-ear results meaningless (SPEC §2.2 trap 11).
 * `null` = could not be read on this device.
 */
data class EnvSnapshot(
    val mono: Boolean? = null,
    val balance: Float? = null,
    val spatializerOn: Boolean? = null,
    val dndOn: Boolean? = null,
)
