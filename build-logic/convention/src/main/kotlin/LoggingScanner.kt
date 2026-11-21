/**
 * Pure scanner behind `verifyNoSensitiveLogging` (SPEC section 6 / 9 "no hearing data in logs", DECISIONS section 29).
 *
 * ALLOWLIST model. It tokenises a source file (comments, `"..."` with escapes, raw `"""..."""`, nested `${ ... }`
 * templates, char literals) and finds every log / print sink. A sink's message arguments may only be CONSTANT TEXT;
 * anything else is a violation, so a thresholds list, a data class, an exception, a message, `toString()` or any
 * other variable cannot reach a log, whatever it is called.
 *
 * Sinks:
 *  - `Log.v/d/i/w/e/wtf/println` (also `android.util.Log.` qualified): argument 0 is the tag (a constant string, or a
 *    constant named `TAG` / `*_TAG`); for `Log.println` argument 0 is the priority and 1 the tag;
 *  - `println` / `print`, `kotlin.io.println` / `print`, any call on `System.out` / `System.err`;
 *  - `Timber.v/d/i/w/e/wtf/log` and `Timber.tag(..).d(..)`;
 *  - `java.util.logging`: `<...logger...>.severe/warning/info/config/fine/finer/finest/log/...` and
 *    `Logger.getLogger(..).info(..)`.
 *
 * Message terms (joined by `+`) may be:
 *  1. a string literal (normal or raw) whose every template, `$x` or `${...}`, is itself an allowed term. A bare
 *     `$x` is therefore a violation;
 *  2. a numeric literal (optionally negative), a char literal, `true` / `false`;
 *  3. a class name: `<chain>.javaClass.simpleName`, `<chain>::class.simpleName`, `<chain>::class.java.simpleName`,
 *     where `<chain>` is only identifiers joined by `.` / `?.` (no calls, no indexing);
 *  4. an enum constant name: an identifier chain ending in `.<UPPER_CASE>.name`. Without type information this is the
 *     only shape recognised as an enum; `profile.name` is NOT allowed (it would be a user-chosen name);
 *  5. a priority constant: `Log.<UPPER_CASE>` / `Level.<UPPER_CASE>`.
 * A throwable in any position is not one of these, so it is a violation without special handling. Named arguments
 * (`msg = "x"`) are allowed with the same rules; a trailing lambda (`Timber.d { ... }`) is not supported and is
 * reported.
 *
 * Also reported: any `printStackTrace` / `getStackTraceString` / `dumpStack` token, a function reference to a sink
 * (`Log::d`, `::println`, a bare `System.out`), and an import alias or static import of `Log` / `Timber` members.
 *
 * Limit: it only knows sinks by name. A project-defined wrapper is not a sink, but it cannot forward a variable to a
 * real sink either (the call inside the wrapper is scanned and rejected).
 */
class LoggingScanner(private val kotlinSyntax: Boolean = true) {
    data class Violation(val line: Int, val message: String)

    fun scan(text: String): List<Violation> = Scan(text, kotlinSyntax).run()

    private enum class K { ID, STR, NUM, CHR, P }

    /** [tmpl]: (start, endExclusive) of every template expression inside a string literal. */
    private class Tok(val k: K, val text: String, val pos: Int, val end: Int, val tmpl: List<Pair<Int, Int>> = emptyList())

    private enum class Kind { LOG, LOG_PRIORITY, MESSAGE }

    private class Scan(val t: String, val kotlin: Boolean) {
        val out = mutableListOf<Violation>()
        val lineStarts: IntArray = buildList {
            add(0)
            t.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

        fun lineOf(pos: Int): Int {
            val i = lineStarts.binarySearch(pos)
            return if (i >= 0) i + 1 else -i - 1
        }

        fun report(pos: Int, msg: String) {
            out += Violation(lineOf(pos), msg)
        }

        // ---------------------------------------------------------------- lexer

        fun at(i: Int, limit: Int): Char = if (i in 0 until limit) t[i] else '\u0000'

        fun skipChar(start: Int, limit: Int): Int {
            var i = start + 1
            if (at(i, limit) == '\\') {
                i += 2
                while (i < limit && t[i] != '\'' && t[i] != '\n') i++
                return minOf(i + 1, limit)
            }
            return if (at(i + 1, limit) == '\'') i + 2 else start + 1
        }

        /** Skips from just after `${` to just after the matching `}`. */
        fun skipBraces(start: Int, limit: Int): Int {
            var depth = 1
            var i = start
            while (i < limit) {
                when {
                    t[i] == '{' -> { depth++; i++ }
                    t[i] == '}' -> { depth--; i++; if (depth == 0) return i }
                    t[i] == '"' -> i = lexString(i, limit, null)
                    t[i] == '\'' -> i = skipChar(i, limit)
                    t.startsWith("//", i) -> while (i < limit && t[i] != '\n') i++
                    t.startsWith("/*", i) -> {
                        val e = t.indexOf("*/", i + 2)
                        i = if (e < 0 || e + 2 > limit) limit else e + 2
                    }
                    else -> i++
                }
            }
            return limit
        }

        /** [start] is at the opening quote(s). Returns the index just after the closing quote(s). */
        fun lexString(start: Int, limit: Int, templates: MutableList<Pair<Int, Int>>?): Int {
            val raw = t.startsWith("\"\"\"", start) && start + 3 <= limit
            var i = start + if (raw) 3 else 1
            while (i < limit) {
                val c = t[i]
                if (raw) {
                    if (c == '"' && t.startsWith("\"\"\"", i)) {
                        i += 3
                        while (i < limit && t[i] == '"') i++
                        return i
                    }
                } else {
                    if (c == '\\') { i += 2; continue }
                    if (c == '"') return i + 1
                    if (c == '\n') return i // unterminated: stop at the line end rather than swallowing the file
                }
                if (kotlin && c == '$') {
                    val n = at(i + 1, limit)
                    if (n == '{') {
                        val e = skipBraces(i + 2, limit)
                        templates?.add(i + 2 to maxOf(i + 2, e - 1))
                        i = e
                        continue
                    }
                    if (n.isLetter() || n == '_') {
                        var j = i + 1
                        while (j < limit && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                        templates?.add(i + 1 to j)
                        i = j
                        continue
                    }
                }
                i++
            }
            return limit
        }

        fun lex(from: Int, to: Int): List<Tok> {
            val toks = ArrayList<Tok>()
            var i = from
            while (i < to) {
                val c = t[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && at(i + 1, to) == '/' -> while (i < to && t[i] != '\n') i++
                    c == '/' && at(i + 1, to) == '*' -> {
                        var depth = 1
                        i += 2
                        while (i < to && depth > 0) {
                            when {
                                kotlin && t.startsWith("/*", i) -> { depth++; i += 2 }
                                t.startsWith("*/", i) -> { depth--; i += 2 }
                                else -> i++
                            }
                        }
                    }
                    c == '"' -> {
                        val tm = mutableListOf<Pair<Int, Int>>()
                        val e = lexString(i, to, tm)
                        toks += Tok(K.STR, t.substring(i, e), i, e, tm)
                        i = e
                    }
                    c == '\'' -> {
                        val e = skipChar(i, to)
                        toks += Tok(if (e > i + 1) K.CHR else K.P, t.substring(i, e), i, e)
                        i = e
                    }
                    c.isDigit() -> {
                        var j = i
                        while (j < to && (t[j].isLetterOrDigit() || t[j] == '_' || (t[j] == '.' && at(j + 1, to).isDigit()))) j++
                        toks += Tok(K.NUM, t.substring(i, j), i, j)
                        i = j
                    }
                    c.isLetter() || c == '_' || c == '$' -> {
                        var j = i + 1
                        while (j < to && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '$')) j++
                        toks += Tok(K.ID, t.substring(i, j), i, j)
                        i = j
                    }
                    c == '`' -> {
                        var j = i + 1
                        while (j < to && t[j] != '`' && t[j] != '\n') j++
                        j = minOf(j + 1, to)
                        toks += Tok(K.ID, t.substring(i, j), i, j)
                        i = j
                    }
                    else -> {
                        val two = if (i + 1 < to) t.substring(i, i + 2) else ""
                        val len = if (two in TWO) 2 else 1
                        toks += Tok(K.P, t.substring(i, i + len), i, i + len)
                        i += len
                    }
                }
            }
            return toks
        }

        // ---------------------------------------------------------------- argument rules

        fun isP(tok: Tok?, s: String) = tok != null && tok.k == K.P && tok.text == s

        fun src(a: List<Tok>) = t.substring(a.first().pos, a.last().end).replace(Regex("\\s+"), " ").take(70)

        /** Null when [a] (a `+`-joined message expression) is constant text. */
        fun checkValue(a: List<Tok>): String? {
            var toks = a
            if (toks.size >= 2 && toks[0].k == K.ID && isP(toks[1], "=")) toks = toks.drop(2)
            if (toks.isEmpty()) return null
            val terms = mutableListOf<MutableList<Tok>>(mutableListOf())
            for (x in toks) if (isP(x, "+")) terms += mutableListOf<Tok>() else terms.last() += x
            for (term in terms) checkTerm(term)?.let { return it }
            return null
        }

        fun checkTerm(term: List<Tok>): String? {
            if (term.isEmpty()) return "an empty operand"
            val bad = "`${src(term)}`"
            if (term.size == 1) {
                val x = term[0]
                when {
                    x.k == K.NUM || x.k == K.CHR -> return null
                    x.k == K.ID && (x.text == "true" || x.text == "false") -> return null
                    x.k == K.STR -> {
                        for ((s, e) in x.tmpl) {
                            val inner = lex(s, e)
                            val r = if (inner.isEmpty()) null else checkValue(inner)
                            if (r != null) return "template `${t.substring(s, e).trim().take(50)}` in $bad is not an allowed constant"
                        }
                        return null
                    }
                }
            }
            if (term.size == 2 && isP(term[0], "-") && term[1].k == K.NUM) return null
            val plain = term.all { it.k == K.ID || isP(it, ".") || isP(it, "?.") || isP(it, "::") }
            if (plain && term.first().k == K.ID && term.last().k == K.ID) {
                val s = term.joinToString("") { it.text }.replace("?.", ".")
                val segs = s.split('.', ':').filter { it.isNotEmpty() }
                val ok = s == "javaClass.simpleName" ||
                    s.endsWith(".javaClass.simpleName") ||
                    s.endsWith("::class.simpleName") ||
                    s.endsWith("::class.java.simpleName") ||
                    (!s.contains("::") && segs.size >= 2 && segs.last() == "name" && UPPER.matches(segs[segs.size - 2])) ||
                    (!s.contains("::") && segs.size == 2 && (segs[0] == "Log" || segs[0] == "Level") && UPPER.matches(segs[1]))
                if (ok) return null
            }
            return "$bad is not an allowed constant"
        }

        fun checkTag(a: List<Tok>): String? {
            if (a.isNotEmpty() && a.all { it.k == K.ID || isP(it, ".") } && a.last().k == K.ID &&
                (a.last().text == "TAG" || a.last().text.endsWith("_TAG"))
            ) return null
            return checkValue(a)
        }

        // ---------------------------------------------------------------- sinks

        fun kindOf(segs: List<String>): Kind? {
            val n = segs.size
            val last = segs.last()
            if (n >= 2 && segs[n - 2] == "Log" && last in LOG_LEVELS) return if (last == "println") Kind.LOG_PRIORITY else Kind.LOG
            if (n == 1 && (last == "println" || last == "print")) return Kind.MESSAGE
            if (n == 3 && segs[0] == "kotlin" && segs[1] == "io" && (last == "println" || last == "print")) return Kind.MESSAGE
            if ((0..n - 3).any { segs[it] == "System" && (segs[it + 1] == "out" || segs[it + 1] == "err") }) return Kind.MESSAGE
            if (n == 2 && segs[0] == "Timber" && last in TIMBER) return Kind.MESSAGE
            if (n >= 2 && segs[n - 2].lowercase().contains("logger") && last in JUL) return Kind.MESSAGE
            return null
        }

        /** Index of the `)` matching the `(` at [open], or -1. */
        fun matching(toks: List<Tok>, open: Int): Int {
            var depth = 0
            for (i in open until toks.size) {
                val x = toks[i]
                if (x.k != K.P) continue
                if (x.text == "(" || x.text == "[" || x.text == "{") depth++
                if (x.text == ")" || x.text == "]" || x.text == "}") {
                    depth--
                    if (depth == 0) return if (x.text == ")") i else -1
                }
            }
            return -1
        }

        fun splitArgs(toks: List<Tok>, open: Int, close: Int): List<List<Tok>> {
            val args = mutableListOf<MutableList<Tok>>(mutableListOf())
            var depth = 0
            for (i in open + 1 until close) {
                val x = toks[i]
                if (x.k == K.P && (x.text == "(" || x.text == "[" || x.text == "{")) depth++
                if (x.k == K.P && (x.text == ")" || x.text == "]" || x.text == "}")) depth--
                if (x.k == K.P && x.text == "," && depth == 0) args += mutableListOf<Tok>() else args.last() += x
            }
            return args.filter { it.isNotEmpty() }
        }

        fun checkSink(toks: List<Tok>, name: String, kind: Kind, nameTok: Tok, open: Int) {
            val close = matching(toks, open)
            if (close < 0) {
                report(nameTok.pos, "log call `$name(` is not terminated; cannot verify its arguments")
                return
            }
            if (isP(toks.getOrNull(close + 1), "{")) {
                report(nameTok.pos, "`$name` with a trailing lambda is not supported; pass constant text")
            }
            val args = splitArgs(toks, open, close)
            args.forEachIndexed { idx, a ->
                val reason = when {
                    kind == Kind.LOG && idx == 0 -> checkTag(a)
                    kind == Kind.LOG_PRIORITY && idx == 1 -> checkTag(a)
                    else -> checkValue(a)
                }
                if (reason != null) {
                    report(a.first().pos, "log call `$name` argument ${idx + 1}: $reason (log only constant text and class names)")
                }
            }
        }

        fun run(): List<Violation> {
            val toks = lex(0, t.length)
            for (x in toks) {
                if (x.k == K.ID && x.text in STACK) report(x.pos, "`${x.text}` is not allowed (stack traces carry exception messages)")
            }
            var k = 0
            while (k < toks.size) {
                val tok = toks[k]
                if (tok.k != K.ID) { k++; continue }
                val prev = toks.getOrNull(k - 1)
                if (tok.text == "import") {
                    val line = lineOf(tok.pos)
                    val ids = mutableListOf<String>()
                    var j = k + 1
                    while (j < toks.size && lineOf(toks[j].pos) == line) {
                        ids += toks[j].text
                        j++
                    }
                    val asAt = ids.indexOf("as")
                    val sinkNames = setOf("Log", "Timber", "println", "print")
                    val staticMember = listOf("Log", "Timber").any { ids.indexOf(it).let { i -> i >= 0 && i != ids.lastIndex && i != asAt - 1 } }
                    val aliased = asAt > 0 && ids[asAt - 1] in sinkNames
                    if (staticMember || aliased) report(tok.pos, "importing or aliasing a log sink member is not allowed: ${ids.joinToString("")}")
                    k = j
                    continue
                }
                if (isP(prev, "::") && (tok.text == "println" || tok.text == "print")) {
                    report(tok.pos, "reference to `${tok.text}` is not allowed; call it with constant text")
                }
                if (isP(prev, ".") || isP(prev, "?.")) { k++; continue }
                // identifier chain a.b?.c
                val segs = mutableListOf(tok.text)
                var j = k
                while ((isP(toks.getOrNull(j + 1), ".") || isP(toks.getOrNull(j + 1), "?.")) && toks.getOrNull(j + 2)?.k == K.ID) {
                    segs += toks[j + 2].text
                    j += 2
                }
                val next = toks.getOrNull(j + 1)
                val full = segs.joinToString(".")
                if (isP(next, "::") && segs.last().let { it == "Log" || it == "Timber" || it == "Logger" } &&
                    toks.getOrNull(j + 2)?.text != "class"
                ) report(tok.pos, "reference to `$full::...` is not allowed")
                if (segs.size >= 2 && (0..segs.size - 2).any { segs[it] == "System" && (segs[it + 1] == "out" || segs[it + 1] == "err") } &&
                    (segs.last() == "out" || segs.last() == "err") && !isP(next, "(")
                ) report(tok.pos, "`$full` must not be used as a value; it is a log sink")
                if (isP(next, "{") && kindOf(segs) != null) {
                    report(tok.pos, "`$full` with a lambda is not supported; pass constant text")
                }
                if (isP(next, "(")) {
                    val kind = kindOf(segs)
                    if (kind != null) checkSink(toks, full, kind, tok, j + 1)
                    // Timber.tag(..).d(..) and Logger.getLogger(..).info(..)
                    val chained = segs == listOf("Timber", "tag") || (segs.size >= 2 && segs[segs.size - 2] == "Logger" &&
                        (segs.last() == "getLogger" || segs.last() == "getGlobal"))
                    if (chained) {
                        val close = matching(toks, j + 1)
                        if (close >= 0) {
                            checkSink(toks, full, Kind.MESSAGE, tok, j + 1) // tag / logger name: constant text only
                            if (isP(toks.getOrNull(close + 1), ".") && toks.getOrNull(close + 2)?.k == K.ID &&
                                isP(toks.getOrNull(close + 3), "(")
                            ) {
                                val m = toks[close + 2].text
                                if (m in TIMBER || m in JUL) checkSink(toks, "$full(..).$m", Kind.MESSAGE, toks[close + 2], close + 3)
                            }
                        }
                    }
                }
                k = j + 1
            }
            return out.sortedBy { it.line }
        }
    }

    private companion object {
        val TWO = setOf("==", "!=", "<=", ">=", "::", "?.", "->", "&&", "||", "+=", "-=", "..")
        val LOG_LEVELS = setOf("v", "d", "i", "w", "e", "wtf", "println")
        val TIMBER = setOf("v", "d", "i", "w", "e", "wtf", "log")
        val JUL = setOf("severe", "warning", "info", "config", "fine", "finer", "finest", "log", "entering", "exiting", "throwing", "v", "d", "i", "w", "e")
        val STACK = setOf("printStackTrace", "getStackTraceString", "dumpStack")
        val UPPER = Regex("[A-Z][A-Z0-9_]*")
    }
}
