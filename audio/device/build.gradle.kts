plugins {
    id("paudio.android.library")
    id("paudio.hilt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":testing"))
}
