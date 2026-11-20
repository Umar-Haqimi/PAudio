plugins {
    id("paudio.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Public interfaces expose Flow/StateFlow and kotlinx.serialization types.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
