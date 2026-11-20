import org.gradle.api.Plugin
import org.gradle.api.Project

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        addLib("implementation", "hilt-android")
        addLib("ksp", "hilt-compiler")
        addLib("testImplementation", "hilt-android-testing")
        addLib("androidTestImplementation", "hilt-android-testing")
    }
}
