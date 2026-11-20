import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a `:core:*` project is not plain Kotlin/JVM (SPEC §5.1):
 *  - applies any `com.android.*` plugin,
 *  - depends (directly or transitively) on a `com.android.*`, `androidx.*` or `com.google.android.*` artifact,
 *  - depends on a project that is not itself a `:core:*` project (so e.g. `:designsystem`/`:data` are rejected).
 */
abstract class VerifyPureKotlinTask : DefaultTask() {
    @get:Input
    abstract val androidPluginsApplied: ListProperty<String>

    /** Root nodes of the resolved compile/runtime/test classpaths of the project. */
    @get:Input
    abstract val resolvedRoots: ListProperty<ResolvedComponentResult>

    @get:Input
    abstract val projectPath: Property<String>

    @TaskAction
    fun verify() {
        val problems = mutableListOf<String>()
        androidPluginsApplied.get().forEach { problems += "applies Android Gradle plugin '$it'" }

        val seen = mutableSetOf<String>()
        fun walk(c: ResolvedComponentResult) {
            val id = c.id
            if (!seen.add(id.displayName)) return
            when (id) {
                is ModuleComponentIdentifier -> if (FORBIDDEN_GROUPS.any { id.group == it || id.group.startsWith("$it.") }) {
                    problems += "depends on Android artifact ${id.displayName}"
                }
                is ProjectComponentIdentifier -> if (!id.projectPath.startsWith(":core:") && id.projectPath != projectPath.get()) {
                    problems += "depends on non-core project ${id.projectPath}"
                }
            }
            c.dependencies.forEach { dep ->
                when (dep) {
                    is ResolvedDependencyResult -> walk(dep.selected)
                    // e.g. a JVM project depending on an Android library cannot even select a variant.
                    is UnresolvedDependencyResult ->
                        problems += "has unresolvable dependency ${dep.requested.displayName} (${dep.failure.message})"
                }
            }
        }
        resolvedRoots.get().forEach(::walk)

        if (problems.isNotEmpty()) {
            throw GradleException(
                "${projectPath.get()} must be pure Kotlin/JVM (SPEC §5.1) but:\n" +
                    problems.distinct().joinToString("\n") { " - $it" },
            )
        }
    }

    private companion object {
        val FORBIDDEN_GROUPS = listOf("com.android", "androidx", "com.google.android")
    }
}
