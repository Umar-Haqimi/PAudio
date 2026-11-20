import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Applied by the root build to every `:core:*` project. Registers `verifyPureKotlin` and wires it into `check`.
 * See [VerifyPureKotlinTask].
 */
class CoreGuardConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val androidPlugins = mutableListOf<String>()
        // Catches Android plugins applied before or after this plugin.
        plugins.whenPluginAdded {
            if (this.javaClass.name.startsWith("com.android.")) androidPlugins += this.javaClass.name
        }
        plugins.forEach { if (it.javaClass.name.startsWith("com.android.")) androidPlugins += it.javaClass.name }

        val verify = tasks.register<VerifyPureKotlinTask>("verifyPureKotlin") {
            group = "verification"
            description = "Fails if this :core module applies an Android plugin or depends on Android artifacts/projects."
            projectPath.set(target.path)
            androidPluginsApplied.set(providers.provider { androidPlugins.distinct() })
        }

        // Classpath configurations only exist once the Java plugin (applied by kotlin("jvm")) is there.
        pluginManager.withPlugin("java") {
            verify.configure {
                listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
                    resolvedRoots.add(configurations.getByName(name).incoming.resolutionResult.rootComponent)
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}
