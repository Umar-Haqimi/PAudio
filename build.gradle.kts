// Root build: plugin versions are declared once here (apply false); modules use convention plugins from build-logic.
plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.androidx.room) apply false
    id("paudio.core.guard") apply false
}

// SPEC §5.1: `:core:*` must stay plain Kotlin/JVM. Enforced centrally so a module cannot opt out.
subprojects {
    if (path.startsWith(":core:")) {
        apply(plugin = "paudio.core.guard")
    }
}

// SPEC §2.2 trap 19: no direct use of experimental shape/expressive APIs in feature modules.
val verifyFeatureBoundaries = tasks.register<VerifyFeatureBoundariesTask>("verifyFeatureBoundaries") {
    group = "verification"
    description = "Fails if feature/** uses MaterialShapes, graphics-shapes or @ExperimentalMaterial3ExpressiveApi."
    // Hand-written sources only: generated code under feature/*/build is another task's output and
    // would make this an implicit (undeclared) dependency of KSP/Hilt tasks.
    sources.from(fileTree("feature") { include("*/src/**/*.kt"); exclude("**/build/**") })
    forbiddenPatterns.set(VerifyFeatureBoundariesTask.FORBIDDEN)
    marker.set(layout.buildDirectory.file("reports/feature-boundaries.txt"))
}
tasks.named("check") { dependsOn(verifyFeatureBoundaries) }
