import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * SPEC §2.2 trap 19: experimental Material 3 / shapes APIs (`MaterialShapes`, graphics-shapes, anything marked
 * `@ExperimentalMaterial3ExpressiveApi`) are wrapped in `:designsystem`; feature modules must not use them directly.
 */
abstract class VerifyFeatureBoundariesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenPatterns: ListProperty<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val patterns = forbiddenPatterns.get()
        val violations = mutableListOf<String>()
        sources.files.filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                patterns.firstOrNull { line.contains(it) }?.let {
                    violations += "${file.path}:${i + 1} uses '$it'"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "feature/** must not use experimental/shape APIs directly (SPEC §2.2 trap 19); wrap them in :designsystem:\n" +
                    violations.joinToString("\n") { " - $it" },
            )
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }

    companion object {
        val FORBIDDEN = listOf(
            "androidx.compose.material3.MaterialShapes",
            "androidx.graphics.shapes",
            "ExperimentalMaterial3ExpressiveApi",
        )
    }
}
