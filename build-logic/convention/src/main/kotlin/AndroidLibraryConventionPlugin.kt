import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library with namespace derived from the project path. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // AGP 9 has built-in Kotlin support: do NOT apply org.jetbrains.kotlin.android.
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = namespaceFromPath()
            configureAndroidCommon(this)
            testOptions.targetSdk = TARGET_SDK
        }
        addCommonTestDependencies()
        addLib("implementation", "kotlinx-coroutines-core")
    }
}
