package my.umarh.paudio.feature.settings

/** License families used by the hand-written list (no Play Services OSS plugin). */
enum class LicenseKind { APACHE_2, MIT }

/**
 * One row of the open-source licenses list. [modules] are the Maven `group:artifact` coordinates (as in
 * `gradle/libs.versions.toml`) the entry covers; a unit test checks that every runtime library there is covered.
 */
data class OpenSourceLicense(val name: String, val kind: LicenseKind, val modules: List<String>)

/** Libraries that ship in the app (runtime classpath), grouped by project. */
val OpenSourceLicenses: List<OpenSourceLicense> = listOf(
    OpenSourceLicense(
        "Jetpack Compose (UI, Foundation, Graphics, Material 3)",
        LicenseKind.APACHE_2,
        listOf(
            "androidx.compose:compose-bom",
            "androidx.compose.ui:ui",
            "androidx.compose.ui:ui-graphics",
            "androidx.compose.ui:ui-tooling-preview",
            "androidx.compose.foundation:foundation",
            "androidx.compose.material3:material3",
        ),
    ),
    OpenSourceLicense(
        "AndroidX Core, Activity, Lifecycle and Navigation",
        LicenseKind.APACHE_2,
        listOf(
            "androidx.core:core-ktx",
            "androidx.activity:activity-compose",
            "androidx.lifecycle:lifecycle-runtime-compose",
            "androidx.lifecycle:lifecycle-viewmodel-compose",
            "androidx.lifecycle:lifecycle-service",
            "androidx.navigation:navigation-compose",
        ),
    ),
    OpenSourceLicense(
        "AndroidX Room and DataStore",
        LicenseKind.APACHE_2,
        listOf(
            "androidx.room:room-runtime",
            "androidx.room:room-ktx",
            "androidx.datastore:datastore-preferences",
        ),
    ),
    OpenSourceLicense(
        "AndroidX Media3 (ExoPlayer)",
        LicenseKind.APACHE_2,
        listOf("androidx.media3:media3-exoplayer", "androidx.media3:media3-common"),
    ),
    OpenSourceLicense("AndroidX Graphics Shapes", LicenseKind.APACHE_2, listOf("androidx.graphics:graphics-shapes")),
    OpenSourceLicense("MaterialKolor", LicenseKind.MIT, listOf("com.materialkolor:material-kolor")),
    OpenSourceLicense(
        "Dagger and Hilt",
        LicenseKind.APACHE_2,
        listOf("com.google.dagger:hilt-android", "androidx.hilt:hilt-navigation-compose"),
    ),
    OpenSourceLicense(
        "Kotlin coroutines and serialization",
        LicenseKind.APACHE_2,
        listOf(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
        ),
    ),
)
