import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe cases for the logging allowlist (DECISIONS section 29). In the snippets `§` stands for `$` so the Kotlin
 * templates need no escaping in this file.
 */
class LoggingScannerTest {
    private fun scan(code: String, kotlin: Boolean = true) = LoggingScanner(kotlin).scan(code.replace('§', '$'))

    private fun assertFlagged(code: String) {
        val v = scan(code)
        assertTrue("expected a violation for: $code", v.isNotEmpty())
    }

    private fun assertClean(code: String) {
        val v = scan(code)
        assertTrue("unexpected violation for: $code -> $v", v.isEmpty())
    }

    // ---- reviewer's probes: every one must be flagged

    @Test fun logDWithTemplateOfAVariable() = assertFlagged("""Log.d("T", "§thresholds")""")
    @Test fun systemOutPrintln() = assertFlagged("""System.out.println("x §thresholds")""")
    @Test fun systemErrPrintln() = assertFlagged("""System.err.println("x §thresholds")""")
    @Test fun kotlinIoPrintln() = assertFlagged("""kotlin.io.println("x §thresholds")""")
    @Test fun barePrintlnOfVariable() = assertFlagged("""println(thresholds)""")
    @Test fun logWTwoArgThrowable() = assertFlagged("""Log.w("T", e)""")
    @Test fun logWtfTwoArgThrowable() = assertFlagged("""Log.wtf("T", e)""")
    @Test fun logWThreeArgThrowable() = assertFlagged("""Log.w("T", "failed", e)""")
    @Test fun logETemplateOfThrowable() = assertFlagged("""Log.e("T", "§e")""")
    @Test fun logEToString() = assertFlagged("""Log.e("T", e.toString())""")
    @Test fun logEMessage() = assertFlagged("""Log.e("T", e.message ?: "")""")
    @Test fun printStackTrace() = assertFlagged("""e.printStackTrace()""")
    @Test fun printStackTraceOnCallResult() = assertFlagged("""foo().bar?.printStackTrace()""")
    @Test fun getStackTraceString() = assertFlagged("""Log.d("T", Log.getStackTraceString(e))""")
    @Test fun identifierNotInAnyDenylist() = assertFlagged("""Log.d("T", "g=§bandGains")""")
    @Test fun braceTemplateOfPta() = assertFlagged("""Log.i("T", "x=${'§'}{pta}")""")
    @Test fun leftHl() = assertFlagged("""Log.i("T", "§leftHl")""")
    @Test fun audiogram() = assertFlagged("""Log.i("T", audiogram.toString())""")
    @Test fun dataClassResultTemplate() = assertFlagged("""Log.i("T", "§result")""")
    @Test fun dataClassResultBraceTemplate() = assertFlagged("""Log.i("T", "r=§{result}")""")
    @Test fun qualifiedAndroidUtilLog() = assertFlagged("""android.util.Log.w("PAudio", "fault: §{e.message}")""")
    @Test fun messageIsAVariable() = assertFlagged("""Log.d("T", msg)""")
    @Test fun messageIsAConstantReference() = assertFlagged("""Log.d("T", MESSAGE)""")
    @Test fun concatenationWithVariable() = assertFlagged("""Log.d("T", "a" + gains)""")
    @Test fun functionCallInMessage() = assertFlagged("""Log.d("T", String.format("%d", n))""")
    @Test fun nonTagVariable() = assertFlagged("""Log.d(tag, "x")""")
    @Test fun namedArgumentWithVariable() = assertFlagged("""Log.d(tag = "T", msg = thresholds)""")
    @Test fun printfOnSystemOut() = assertFlagged("""System.out.printf("%s", thresholds)""")
    @Test fun printNoNewline() = assertFlagged("""print(thresholds)""")
    @Test fun timberVariable() = assertFlagged("""Timber.d(thresholds.toString())""")
    @Test fun timberTagThenVariable() = assertFlagged("""Timber.tag("T").w(e)""")
    @Test fun timberLambda() = assertFlagged("""Timber.d { "x" }""")
    @Test fun julLogger() = assertFlagged("""logger.info("x §y")""")
    @Test fun julGetLogger() = assertFlagged("""Logger.getLogger("T").severe(e.toString())""")
    @Test fun logPrintlnWithVariable() = assertFlagged("""Log.println(Log.INFO, "T", payload)""")
    @Test fun nestedTemplateWithStringInside() = assertFlagged("""Log.d("T", "a ${'§'}{foo("x}")} b")""")
    @Test fun functionReferenceToLog() = assertFlagged("""val f = Log::d""")
    @Test fun functionReferenceToPrintln() = assertFlagged("""list.forEach(::println)""")
    @Test fun systemOutAsValue() = assertFlagged("""val out = System.out""")
    @Test fun importAlias() = assertFlagged("import android.util.Log as L\n")
    @Test fun staticImportOfLogMember() = assertFlagged("import android.util.Log.d\n")
    @Test fun starImportOfLog() = assertFlagged("import android.util.Log.*\n")
    @Test fun templateAfterCommentsIsStillFound() = assertFlagged("Log.d(/* x */ \"T\", // c\n  \"§y\")")
    @Test fun rawStringTemplate() = assertFlagged("Log.d(\"T\", \"\"\"a §y\"\"\")")
    @Test fun unterminatedCallIsReported() = assertFlagged("""Log.d("T", "x"""")

    @Test fun javaSystemOutConcat() =
        assertTrue(scan("""System.out.println("x" + thresholds);""", kotlin = false).isNotEmpty())

    @Test fun javaPrintStackTrace() =
        assertTrue(scan("""e.printStackTrace();""", kotlin = false).isNotEmpty())

    // ---- false positives that the old regex scanner reported: must pass

    @Test fun commasInsideAStringAreNotArguments() = assertClean("""Log.i("T", "constant, with, commas")""")
    @Test fun trailingBackslashInString() = assertClean("Log.i(\"T\", \"path \\\\\")\nval x = 1\nLog.i(\"T\", \"ok\")")
    @Test fun escapedQuoteInString() = assertClean("Log.i(\"T\", \"say \\\"hi, there\\\" now\")")
    @Test fun parenthesesInsideString() = assertClean("""Log.i("T", "retry (3) failed)")""")
    @Test fun sensitiveWordsInConstantTextAreFine() = assertClean("""Log.i("T", "profile threshold device name")""")

    // ---- allowed forms

    @Test fun tagConstantAndClassName() = assertClean("""Log.w(TAG, "failed: ${'§'}{e.javaClass.simpleName}")""")
    @Test fun tagQualifiedConstant() = assertClean("""Log.w(Companion.TAG, "x")""")
    @Test fun tagWithSuffix() = assertClean("""Log.w(DP_TAG, "x")""")
    @Test fun qualifiedLogWithClassName() =
        assertClean("""android.util.Log.w("PAudio", "service fault: ${'§'}{e.javaClass.simpleName}")""")
    @Test fun simpleDollarIdentifierOfClassNameIsNotAllowedButBraceFormIs() {
        assertFlagged("""Log.w(TAG, "§e")""")
        assertClean("""Log.w(TAG, "x ${'§'}{e::class.simpleName} y")""")
    }
    @Test fun classLiteralJavaSimpleName() = assertClean("""Log.d("T", "x " + Foo::class.java.simpleName)""")
    @Test fun safeCallClassName() = assertClean("""Log.d("T", "${'§'}{t?.javaClass?.simpleName}")""")
    @Test fun thisJavaClass() = assertClean("""Log.d("T", this.javaClass.simpleName)""")
    @Test fun enumNameOfConstant() = assertClean("""Log.d("T", "mode " + Mode.FAST.name)""")
    @Test fun enumNameInTemplate() = assertClean("""Log.d("T", "mode ${'§'}{Mode.FAST.name}")""")
    @Test fun lowercaseDotNameIsNotAnEnum() = assertFlagged("""Log.d("T", "x ${'§'}{profile.name}")""")
    @Test fun numericLiterals() = assertClean("""Log.d("T", "n=" + 3 + " " + -1 + " " + 0.5f + " " + 0xFF)""")
    @Test fun numericTemplate() = assertClean("""Log.d("T", "n=${'§'}{12}")""")
    @Test fun literalConcatenation() = assertClean("""Log.d("T", "a" + "b")""")
    @Test fun namedArgumentsWithConstants() = assertClean("""Log.d(tag = "T", msg = "x")""")
    @Test fun rawStringConstant() = assertClean("Log.d(\"T\", \"\"\"multi\nline, \"quoted\" text\"\"\")")
    @Test fun charTemplateForDollar() = assertClean("""Log.d("T", "cost ${'§'}{'${'§'}'}")""")
    @Test fun multiLineCall() = assertClean("Log.w(\n    TAG,\n    \"create failed (\${e.javaClass.simpleName})\",\n)")
    @Test fun printlnOfConstant() = assertClean("""println("started")""")
    @Test fun systemOutOfConstant() = assertClean("""System.out.println("started")""")
    @Test fun logPrintlnWithPriorityConstant() = assertClean("""Log.println(Log.INFO, "T", "hello")""")
    @Test fun timberConstant() = assertClean("""Timber.tag("T").d("x ${'§'}{e.javaClass.simpleName}")""")
    @Test fun julConstant() = assertClean("""logger.info("hello")""")
    @Test fun noSinkNoViolation() = assertClean("""val s = "§thresholds"; foo(s); x.print(gains); catalog.d(gains); dialog.e(x)""")
    @Test fun commentsAreIgnored() = assertClean("// Log.d(\"T\", thresholds)\n/* println(gains) /* nested */ e.printStackTrace() */\nval x = 1")
    @Test fun importOfLogAndTimberIsFine() = assertClean("import android.util.Log\nimport timber.log.Timber\n")
    @Test fun logClassLiteralIsNotAReference() = assertClean("""val k = Log::class""")
    @Test fun javaClassNameConstants() =
        assertTrue(scan("""System.out.println("a, b" + 3);""", kotlin = false).isEmpty())

    // ---- reporting

    @Test fun reportsTheLineOfTheOffendingArgument() {
        val v = scan("val a = 1\nLog.d(\n  \"T\",\n  \"§x\",\n)\n")
        assertEquals(1, v.size)
        assertEquals(4, v[0].line)
    }

    @Test fun oneBadCallDoesNotHideAnother() {
        val v = scan("Log.d(\"T\", a)\nLog.d(\"T\", \"ok\")\nLog.d(\"T\", b)\n")
        assertEquals(listOf(1, 3), v.map { it.line })
    }
}
