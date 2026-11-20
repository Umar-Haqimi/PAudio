plugins {
    id("paudio.android.library")
    id("paudio.android.compose")
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.material.kolor)
    api(libs.androidx.graphics.shapes)
    implementation(project(":core:model"))
}
