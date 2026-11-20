plugins {
    id("paudio.android.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
}
