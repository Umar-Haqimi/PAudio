plugins {
    id("paudio.android.library")
    id("paudio.hilt")
}

dependencies {
    implementation(project(":core:model"))
    // DpLadderStep / DpEqPlan are part of this module's public API.
    api(project(":core:dsp"))
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
    androidTestImplementation(project(":testing"))
}
