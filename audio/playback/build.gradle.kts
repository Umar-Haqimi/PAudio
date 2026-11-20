plugins {
    id("paudio.android.library")
    id("paudio.hilt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:dsp"))
    implementation(project(":core:preference"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testing"))
    testImplementation(libs.androidx.media3.test.utils)
    androidTestImplementation(project(":testing"))
}
