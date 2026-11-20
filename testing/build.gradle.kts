plugins {
    id("paudio.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    api(libs.truth)
    api(libs.junit)
    api(libs.turbine)
}
