package my.umarh.paudio.core.model

/** Where [AbPlayer] reads audio from: a bundled asset or a user-picked content URI (kept as a string). */
sealed interface AudioSource {
    data class Asset(val path: String) : AudioSource
    data class Uri(val value: String) : AudioSource
}
