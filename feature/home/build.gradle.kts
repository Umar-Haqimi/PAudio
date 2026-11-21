plugins {
    id("paudio.android.feature")
}

dependencies {
    implementation(project(":data"))
    implementation(project(":service"))
    implementation(project(":core:audiometry"))

    testImplementation(project(":testing"))
}
