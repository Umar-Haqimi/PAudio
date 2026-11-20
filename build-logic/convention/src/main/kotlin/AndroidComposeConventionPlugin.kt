import org.gradle.api.Plugin
import org.gradle.api.Project

/** Compose compiler plugin + BOM + the Compose artifacts every UI module needs. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        addPlatformLib("implementation", "androidx-compose-bom")
        addPlatformLib("testImplementation", "androidx-compose-bom")
        addPlatformLib("androidTestImplementation", "androidx-compose-bom")
        listOf(
            "androidx-compose-ui",
            "androidx-compose-ui-graphics",
            "androidx-compose-ui-tooling-preview",
            "androidx-compose-foundation",
            "androidx-compose-material3",
        ).forEach { addLib("implementation", it) }
        addLib("debugImplementation", "androidx-compose-ui-tooling")
        // Compose UI tests (Robolectric unit tests and instrumented tests).
        addLib("testImplementation", "androidx-compose-ui-test-junit4")
        addLib("androidTestImplementation", "androidx-compose-ui-test-junit4")
        addLib("debugImplementation", "androidx-compose-ui-test-manifest")
    }
}
