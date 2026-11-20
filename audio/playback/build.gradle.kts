plugins {
    id("paudio.android.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
}
