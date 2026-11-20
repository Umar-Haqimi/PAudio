plugins {
    id("paudio.android.library")
    id("paudio.hilt")
}

dependencies {
    // EngineState / EngineController are the public API features consume.
    api(project(":audio:effect"))
    api(project(":core:model"))
    api(project(":data"))
    implementation(project(":core:dsp"))
    implementation(project(":core:audiometry"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":testing"))
}
