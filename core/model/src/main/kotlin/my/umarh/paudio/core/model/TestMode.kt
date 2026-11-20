package my.umarh.paudio.core.model

import kotlinx.serialization.Serializable

/** Quick = yes/no responses with adaptive levels; Thorough = the user adjusts the level (SPEC §1). */
@Serializable
enum class TestMode { QUICK, THOROUGH }
