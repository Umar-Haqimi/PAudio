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
 * SPEC §6 / §9 "no hearing data in logs", enforced statically (M12, DECISIONS §26).
 *
 * Scans hand-written production sources (`src/main`) for logging calls (`Log.*`, `println`/`print`, `Timber.*`,
 * `printStackTrace`, `Logger`) and fails if the call text mentions anything that could carry hearing or identity
 * data: thresholds, HL values, curves, gains, profile / listener / headset / device names, addresses (MAC, BT),
 * package names, or an exception message (`.message`, `.localizedMessage`, a throwable argument, stack-trace
 * helpers; messages can embed arbitrary data). Logging the exception CLASS name (`javaClass.simpleName`) and
 * constant phrases is allowed. Statements are matched across lines up to the closing parenthesis.
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
        sources.files.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
            violations += scan(file.readText()).map { "${file.path}:${it.first}: ${it.second}" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Logging calls must not mention hearing data, names, addresses or exception messages " +
                    "(SPEC §6, docs/PRIVACY.md):\n" + violations.joinToString("\n") { " - $it" },
            )
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }

    companion object {
        /** A logging sink. The lookbehind keeps `catalog.` / `dialog.` from matching `log.`-style names. */
        private val CALL = Regex(
            """(?<![\w.])(?:(?:android\.util\.)?Log\.[a-z]+|Timber\.[a-z]+|println|print|Logger\.[a-z]+)\s*\(""" +
                """|\.printStackTrace\s*\(|getStackTraceString""",
        )

        /** Identifiers that may carry user / hearing data. */
        private val SENSITIVE = Regex(
            """(?i)threshold|\bhl\b|hlDb|\bhl[A-Z_]|dbhl|curve|\bgains?\b|compstrength|listener|profile|headset|device|""" +
                """product|address|\bmac\b|bssid|\bssid\b|\bname\b|package|trial|lref|\.message|localizedMessage|""" +
                """getStackTraceString|printStackTrace|\.cause\b|stackTrace""",
        )

        private val ALLOWED_SNIPPETS = listOf("javaClass.simpleName", "::class.simpleName")

        /** Returns (line number, reason) pairs for one source text. */
        fun scan(text: String): List<Pair<Int, String>> {
            val out = mutableListOf<Pair<Int, String>>()
            for (m in CALL.findAll(text)) {
                val start = m.range.first
                if (isCommentLine(text, start)) continue
                val line = text.substring(0, start).count { it == '\n' } + 1
                val call = callText(text, m)
                var scrubbed = call
                ALLOWED_SNIPPETS.forEach { scrubbed = scrubbed.replace(it, "") }
                // A throwable argument (Log.w(tag, msg, e)) prints the stack trace and message.
                if (Regex("""Log\.[a-z]+\([^,]+,[^,]+,""").containsMatchIn(call.replace("\n", " "))) {
                    out += line to "logs a throwable/extra argument: ${call.take(80)}"
                    continue
                }
                SENSITIVE.find(scrubbed)?.let { out += line to "mentions '${it.value}': ${call.take(80)}" }
            }
            return out
        }

        private fun isCommentLine(text: String, idx: Int): Boolean {
            val ls = text.lastIndexOf('\n', idx) + 1
            return text.substring(ls, idx).trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        }

        /** The call text from its opening parenthesis to the matching close (or end of text). */
        private fun callText(text: String, m: MatchResult): String {
            val open = text.indexOf('(', m.range.first).takeIf { it >= 0 } ?: return m.value
            var depth = 0
            var i = open
            var inStr = false
            while (i < text.length) {
                val c = text[i]
                if (c == '"' && text.getOrNull(i - 1) != '\\') inStr = !inStr
                if (!inStr) {
                    if (c == '(') depth++
                    if (c == ')') {
                        depth--
                        if (depth == 0) return text.substring(m.range.first, i + 1)
                    }
                }
                i++
            }
            return text.substring(m.range.first)
        }
    }
}
