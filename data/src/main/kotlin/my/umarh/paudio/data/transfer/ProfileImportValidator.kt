package my.umarh.paudio.data.transfer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import my.umarh.paudio.core.model.HeadsetJson
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.HearingTestJson
import my.umarh.paudio.core.model.PreferenceTestJson
import my.umarh.paudio.core.model.ProfileFile
import my.umarh.paudio.core.model.ProfileJson
import my.umarh.paudio.core.model.ProfileSettingsJson
import my.umarh.paudio.core.model.ThresholdJson
import my.umarh.paudio.core.model.TrialJson
import my.umarh.paudio.core.model.UnsupportedSchemaException

enum class ImportErrorReason {
    TOO_LARGE, NOT_UTF8, MALFORMED, TOO_DEEP, UNSUPPORTED_VERSION, INVALID_VALUE,
    TOO_MANY_TESTS, TOO_MANY_TRIALS, STORAGE, IO,
}

sealed interface ValidationResult {
    /** A fully validated and clamped document; safe to store. */
    data class Valid(val file: ProfileFile) : ValidationResult
    data class Invalid(val reason: ImportErrorReason, val detail: String) : ValidationResult
}

/**
 * Pure (no Room/Android) validation of UNTRUSTED profile JSON (SPEC §6).
 *
 * Policy: reject > [MAX_BYTES] before parsing; reject non-UTF-8, nesting deeper than [MAX_DEPTH],
 * unknown/missing schemaVersion, NaN/Infinity, more than [MAX_TESTS] tests or [MAX_TRIALS] trials in total
 * (REJECT, never truncate), duplicate (ear, freq) thresholds / duplicate trial seq, blank names.
 * Numerics are CLAMPED: levels -120..0 dBFS, compStrength 0..1, gB -6..12, gP -6..6, gT -8..8, fpRate 0..1.
 * `headset.key` is recomputed from its fields and the recomputed value is used.
 * Never throws.
 */
object ProfileImportValidator {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_TESTS = 100
    const val MAX_TRIALS = 20_000
    const val MAX_DEPTH = 32
    const val MAX_THRESHOLDS_PER_TEST = 256
    const val MAX_NAME = 100
    const val MAX_TEXT = 200

    fun validate(bytes: ByteArray): ValidationResult = try {
        validateUnsafe(bytes)
    } catch (e: Exception) {
        ValidationResult.Invalid(ImportErrorReason.MALFORMED, e.javaClass.simpleName)
    } catch (e: OutOfMemoryError) {
        ValidationResult.Invalid(ImportErrorReason.TOO_LARGE, "out of memory")
    } catch (e: StackOverflowError) {
        ValidationResult.Invalid(ImportErrorReason.TOO_DEEP, "stack overflow")
    }

    private fun validateUnsafe(bytes: ByteArray): ValidationResult {
        if (bytes.size > MAX_BYTES) return bad(ImportErrorReason.TOO_LARGE, "${bytes.size} bytes")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return bad(ImportErrorReason.NOT_UTF8, "invalid UTF-8")
        }
        if (nestingDepth(text) > MAX_DEPTH) return bad(ImportErrorReason.TOO_DEEP, "nesting > $MAX_DEPTH")
        val file = try {
            ProfileJson.decode(text)
        } catch (e: UnsupportedSchemaException) {
            return bad(ImportErrorReason.UNSUPPORTED_VERSION, e.message.orEmpty())
        } catch (e: SerializationException) {
            return bad(ImportErrorReason.MALFORMED, e.message.orEmpty().take(200))
        } catch (e: IllegalArgumentException) {
            return bad(ImportErrorReason.MALFORMED, e.message.orEmpty().take(200))
        }
        return sanitize(file)
    }

    /** Max `[`/`{` nesting outside of strings; iterative so it cannot overflow the stack. */
    internal fun nestingDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '[', '{' -> { depth++; if (depth > max) max = depth }
                ']', '}' -> depth--
            }
        }
        return max
    }

    /** Truncates without splitting a surrogate pair. */
    private fun String.safeTake(n: Int): String {
        if (length <= n) return this
        return if (n > 0 && this[n - 1].isHighSurrogate()) substring(0, n - 1) else substring(0, n)
    }

    private fun bad(reason: ImportErrorReason, detail: String) = ValidationResult.Invalid(reason, detail)

    private class Reject(val reason: ImportErrorReason, val detail: String) : Exception(detail, null, false, false)

    private fun fail(detail: String): Nothing = throw Reject(ImportErrorReason.INVALID_VALUE, detail)

    private fun finite(v: Float, what: String): Float {
        if (v.isNaN() || v.isInfinite()) fail("$what not finite")
        return v
    }

    private fun clamp(v: Float, lo: Float, hi: Float, what: String) = finite(v, what).coerceIn(lo, hi)
    private fun clampOpt(v: Float?, lo: Float, hi: Float, what: String) = v?.let { clamp(it, lo, hi, what) }
    private fun level(v: Float, what: String) = clamp(v, -120f, 0f, what)

    private fun name(s: String, what: String): String {
        val t = s.trim()
        if (t.isEmpty()) fail("$what blank")
        return t.safeTake(MAX_NAME)
    }

    private fun sanitize(file: ProfileFile): ValidationResult = try {
        if (file.tests.size > MAX_TESTS) throw Reject(ImportErrorReason.TOO_MANY_TESTS, "${file.tests.size} tests")
        val trialCount = file.tests.sumOf { (it.trials?.size ?: 0).toLong() }
        if (trialCount > MAX_TRIALS) throw Reject(ImportErrorReason.TOO_MANY_TRIALS, "$trialCount trials")
        if (file.preferenceTests.size > MAX_TESTS) {
            throw Reject(ImportErrorReason.TOO_MANY_TESTS, "${file.preferenceTests.size} preference tests")
        }
        ValidationResult.Valid(
            ProfileFile(
                schemaVersion = ProfileJson.SCHEMA_VERSION,
                listener = file.listener.copy(name = name(file.listener.name, "listener"), createdAt = file.listener.createdAt.coerceAtLeast(0)),
                headset = file.headset?.let(::headset),
                profile = settings(file.profile),
                tests = file.tests.map(::test),
                preferenceTests = file.preferenceTests.map(::pref),
            ),
        )
    } catch (r: Reject) {
        ValidationResult.Invalid(r.reason, r.detail)
    }

    private fun headset(h: HeadsetJson): HeadsetJson {
        val product = h.productName.trim().safeTake(MAX_TEXT)
        val suffix = h.addressSuffix.safeTake(MAX_TEXT)
        // The stored key is never trusted: recompute from the fields.
        val key = HeadsetKey.of(h.type, product, suffix).value
        return h.copy(
            key = key,
            displayName = h.displayName.safeTake(MAX_TEXT),
            productName = product,
            addressSuffix = suffix,
            createdAt = h.createdAt.coerceAtLeast(0),
        )
    }

    private fun settings(p: ProfileSettingsJson) = p.copy(
        name = name(p.name, "profile name"),
        compStrength = clamp(p.compStrength, 0f, 1f, "compStrength"),
        gB = clamp(p.gB, -6f, 12f, "gB"),
        gP = clamp(p.gP, -6f, 6f, "gP"),
        gT = clamp(p.gT, -8f, 8f, "gT"),
        createdAt = p.createdAt.coerceAtLeast(0),
        updatedAt = p.updatedAt.coerceAtLeast(0),
    )

    private fun test(t: HearingTestJson): HearingTestJson {
        if (t.thresholds.size > MAX_THRESHOLDS_PER_TEST) fail("too many thresholds")
        val ths = t.thresholds.map(::threshold)
        if (ths.map { it.ear to it.freqHz }.toSet().size != ths.size) fail("duplicate threshold")
        val trials = t.trials?.map(::trial)
        if (trials != null && trials.map { it.seq }.toSet().size != trials.size) fail("duplicate trial seq")
        return t.copy(
            startedAt = t.startedAt.coerceAtLeast(0),
            durationMs = t.durationMs.coerceAtLeast(0),
            volIndex = t.volIndex.coerceIn(0, 1000),
            volMax = t.volMax.coerceIn(0, 1000),
            lRef = level(t.lRef, "lRef"),
            appVersion = t.appVersion.safeTake(64),
            algoVersion = t.algoVersion.coerceIn(0, 1_000_000),
            fpRate = clampOpt(t.fpRate, 0f, 1f, "fpRate"),
            thresholds = ths,
            trials = trials,
        )
    }

    private fun freq(f: Int): Int {
        if (f !in 20..20_000) fail("freqHz out of range")
        return f
    }

    private fun threshold(t: ThresholdJson) = t.copy(
        freqHz = freq(t.freqHz),
        levelDbfs = t.levelDbfs?.let { level(it, "threshold level") },
        trials = t.trials.coerceIn(0, 10_000),
    )

    private fun trial(t: TrialJson): TrialJson {
        if (t.seq < 0) fail("negative seq")
        if (t.run != null && t.run != "DESC" && t.run != "ASC") fail("bad run")
        return t.copy(
            freqHz = freq(t.freqHz),
            levelDbfs = t.levelDbfs?.let { level(it, "trial level") },
            tMs = t.tMs.coerceAtLeast(0),
        )
    }

    private fun pref(p: PreferenceTestJson) = p.copy(
        startedAt = p.startedAt.coerceAtLeast(0),
        gB = clamp(p.gB, -6f, 12f, "pref gB"),
        gP = clamp(p.gP, -6f, 6f, "pref gP"),
        gT = clamp(p.gT, -8f, 8f, "pref gT"),
    )
}
