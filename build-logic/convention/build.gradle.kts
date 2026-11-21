plugins {
    `kotlin-dsl`
}

group = "my.umarh.paudio.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.androidx.room.gradlePlugin)

    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "paudio.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "paudio.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "paudio.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "paudio.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "paudio.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("hilt") {
            id = "paudio.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("room") {
            id = "paudio.android.room"
            implementationClass = "RoomConventionPlugin"
        }
        register("coreGuard") {
            id = "paudio.core.guard"
            implementationClass = "CoreGuardConventionPlugin"
        }
    }
}
