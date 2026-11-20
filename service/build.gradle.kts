plugins {
    id("paudio.android.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":audio:effect"))
    implementation(project(":audio:device"))
    implementation(libs.androidx.lifecycle.service)
}
