plugins {
    id("paudio.android.application")
    id("paudio.android.compose")
    id("paudio.hilt")
}

dependencies {
    implementation(project(":designsystem"))
    implementation(project(":core:model"))
    implementation(project(":data"))
    implementation(project(":audio:tone"))
    implementation(project(":audio:device"))
    implementation(project(":audio:effect"))
    implementation(project(":audio:playback"))
    implementation(project(":service"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:hearingtest"))
    implementation(project(":feature:results"))
    implementation(project(":feature:health"))
    implementation(project(":feature:preference"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(project(":testing"))
}
