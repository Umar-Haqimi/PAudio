pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        // Mirrors Maven Central; fallback for intermittent HTTP 429s.
        maven("https://plugins.gradle.org/m2/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }
}

rootProject.name = "PAudio"

include(
    ":app",
    ":designsystem",
    ":core:model",
    ":core:dsp",
    ":core:audiometry",
    ":core:preference",
    ":data",
    ":audio:tone",
    ":audio:device",
    ":audio:effect",
    ":audio:playback",
    ":service",
    ":feature:onboarding",
    ":feature:home",
    ":feature:hearingtest",
    ":feature:results",
    ":feature:health",
    ":feature:preference",
    ":feature:profiles",
    ":feature:settings",
    ":testing",
)
