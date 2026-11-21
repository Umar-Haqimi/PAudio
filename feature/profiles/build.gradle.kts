plugins {
    id("paudio.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:audiometry"))

    testImplementation(project(":testing"))
}
