import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * SPEC section 6 / 9 "no hearing data in logs", enforced statically (M12, DECISIONS sections 26.2 and 29).
 *
 * Runs [LoggingScanner] (allowlist: log / print arguments may only be constant text, class names, enum constant
 * names and numeric literals; `printStackTrace` is banned) over every hand-written `src/main` Kotlin / Java file.
 * The rule and its limits are documented on [LoggingScanner].
 */
abstract class VerifyNoSensitiveLoggingTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        sources.files.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.sortedBy { it.path }
            .forEach { file ->
                val scanner = LoggingScanner(kotlinSyntax = file.extension == "kt")
                violations += scanner.scan(file.readText()).map { "${file.path}:${it.line}: ${it.message}" }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Log and print calls may only carry constant text (SPEC section 6, docs/PRIVACY.md, DECISIONS section 29):\n" +
                    violations.joinToString("\n") { " - $it" },
            )
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}
