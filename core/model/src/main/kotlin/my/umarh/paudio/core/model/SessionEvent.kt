package my.umarh.paudio.core.model

/** An audio-session open/close announcement from a player app (broadcast, legacy or later DUMP source). */
sealed interface SessionEvent {
    val session: Int
    val pkg: String

    data class Open(override val session: Int, override val pkg: String) : SessionEvent
    data class Close(override val session: Int, override val pkg: String) : SessionEvent
}
