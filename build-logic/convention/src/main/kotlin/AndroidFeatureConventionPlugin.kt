import org.gradle.api.Plugin
import org.gradle.api.Project

/** `:feature:*` module: Android library + Compose + Hilt + navigation + :designsystem + :core:model. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("paudio.android.library")
        pluginManager.apply("paudio.android.compose")
        pluginManager.apply("paudio.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies.add("implementation", dependencies.project(":designsystem"))
        dependencies.add("implementation", dependencies.project(":core:model"))
        listOf(
            "androidx-navigation-compose",
            "androidx-hilt-navigation-compose",
            "androidx-lifecycle-runtime-compose",
            "androidx-lifecycle-viewmodel-compose",
            "kotlinx-serialization-json",
        ).forEach { addLib("implementation", it) }
    }
}
