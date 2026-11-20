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
    id("paudio.core.guard") apply false
}

// SPEC §5.1: `:core:*` must stay plain Kotlin/JVM. Enforced centrally so a module cannot opt out.
subprojects {
    if (path.startsWith(":core:")) {
        apply(plugin = "paudio.core.guard")
    }
}
