plugins {
    id("paudio.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":service"))

    testImplementation(project(":testing"))
    testImplementation(project(":core:audiometry"))
}
