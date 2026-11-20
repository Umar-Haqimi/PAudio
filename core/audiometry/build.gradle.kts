plugins {
    id("paudio.jvm.library")
    `java-test-fixtures`
}

dependencies {
    // Ear, StereoBuffer, FrequencyGrid; its api() deps (serialization-json) are used by the golden-vector tests.
    implementation(project(":core:model"))
    testFixturesImplementation(project(":core:model"))
}
