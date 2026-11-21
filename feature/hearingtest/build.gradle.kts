plugins {
    id("paudio.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:audiometry"))
    implementation(project(":audio:tone"))
    implementation(project(":audio:device"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    testImplementation(project(":testing"))
    testImplementation(testFixtures(project(":core:audiometry")))
}
