plugins {
    id("paudio.android.library")
    id("paudio.android.compose")
}

// SPEC §2.2 #19: experimental Material 3 Expressive APIs are opted into ONLY here.
kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    api(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    implementation(libs.androidx.graphics.shapes)
    implementation(project(":core:model"))
}
