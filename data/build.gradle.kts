plugins {
    id("paudio.android.library")
    id("paudio.android.room")
    id("paudio.hilt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
}
