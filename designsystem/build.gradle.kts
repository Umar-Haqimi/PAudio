plugins {
    id("paudio.android.library")
    id("paudio.android.compose")
}

dependencies {
    api(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    implementation(libs.androidx.graphics.shapes)
    implementation(project(":core:model"))
}
