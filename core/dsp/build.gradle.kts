plugins {
    id("paudio.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
    // LTAS JSON loader (JsonElement tree parsing; no serialization compiler plugin needed).
    implementation(libs.kotlinx.serialization.json)
}
