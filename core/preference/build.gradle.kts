plugins {
    id("paudio.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
}
