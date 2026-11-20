plugins {
    id("paudio.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:audiometry"))
    implementation(project(":core:dsp"))
    implementation(project(":core:preference"))
    implementation(project(":audio:playback"))

    testImplementation(project(":testing"))
}
