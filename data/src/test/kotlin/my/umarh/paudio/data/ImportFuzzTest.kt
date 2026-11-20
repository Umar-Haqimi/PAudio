package my.umarh.paudio.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import my.umarh.paudio.core.model.Ear
import my.umarh.paudio.core.model.HeadsetJson
import my.umarh.paudio.core.model.HeadsetKey
import my.umarh.paudio.core.model.HearingTestJson
import my.umarh.paudio.core.model.ListenerJson
import my.umarh.paudio.core.model.OutputDeviceType
import my.umarh.paudio.core.model.PreferenceTestJson
import my.umarh.paudio.core.model.ProfileFile
import my.umarh.paudio.core.model.ProfileJson
import my.umarh.paudio.core.model.ProfileSettingsJson
import my.umarh.paudio.core.model.TestMode
import my.umarh.paudio.core.model.ThresholdJson
import my.umarh.paudio.core.model.TrialJson
import my.umarh.paudio.data.transfer.ConflictPolicy
import my.umarh.paudio.data.transfer.ImportResult
import my.umarh.paudio.data.transfer.ProfileImportValidator
import my.umarh.paudio.data.transfer.ProfileImporter
import my.umarh.paudio.data.transfer.ValidationResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

object FuzzSupport {
    fun validFile(): ProfileFile {
        val ths = listOf(
            ThresholdJson(Ear.LEFT, 1000, -40f), ThresholdJson(Ear.RIGHT, 1000, null, lowConfidence = true, trials = 2),
            ThresholdJson(Ear.LEFT, 8000, -20f, censored = true, trials = 4),
        )
        val trials = (0 until 12).map {
            TrialJson(it, if (it % 2 == 0) Ear.LEFT else Ear.RIGHT, 1000, if (it % 5 == 0) null else -30f - it,
                isCatch = it % 5 == 0, response = it % 3 == 0, run = if (it > 8) "ASC" else null, tMs = it * 700L)
        }
        fun test(mode: TestMode, t: Long) = HearingTestJson(
            mode, t, 80_000, 7, 15, -42f, Json.parseToJsonElement("{\"headsetKey\":\"k\",\"x\":[1,{\"y\":null}]}"),
            false, "1.0", 1, 0.05f, true, ths, trials,
        )
        return ProfileFile(
            listener = ListenerJson("Alice", 5),
            headset = HeadsetJson(HeadsetKey.of(OutputDeviceType.BLUETOOTH_A2DP, "Buds", "AB:CD").value, "My Buds",
                OutputDeviceType.BLUETOOTH_A2DP, "Buds", "AB:CD", 7),
            profile = ProfileSettingsJson("Commute", 0.7f, 2f, 1f, -3f, true, 123, false, 10, 20),
            tests = listOf(test(TestMode.QUICK, 100), test(TestMode.THOROUGH, 200)),
            preferenceTests = listOf(PreferenceTestJson(300, 1f, 2f, 3f, Json.parseToJsonElement("[{\"r\":1}]"))),
        )
    }

    val validBytes: ByteArray = ProfileJson.encode(validFile()).toByteArray()

    private val specials = listOf<JsonElement>(
        JsonPrimitive("NaN"), JsonPrimitive("Infinity"), JsonPrimitive(-1e300), JsonPrimitive(1e300), JsonPrimitive(Long.MAX_VALUE),
        JsonPrimitive(Long.MIN_VALUE), JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive(1e999.toString()), JsonNull,
        JsonPrimitive(true), JsonPrimitive(""), JsonPrimitive("x".repeat(5000)), JsonArray(emptyList()), JsonObject(emptyMap()),
        JsonPrimitive(99999), JsonPrimitive(-999.5), JsonPrimitive(1e-40),
    )

    private fun deepNest(depth: Int): JsonElement {
        var e: JsonElement = JsonPrimitive(1)
        repeat(depth) { e = JsonArray(listOf(e)) }
        return e
    }

    /** Collects paths to all containers/values. */
    private fun paths(e: JsonElement, cur: List<Any>, out: MutableList<List<Any>>) {
        out += cur
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> paths(v, cur + k, out) }
            is JsonArray -> e.forEachIndexed { i, v -> paths(v, cur + i, out) }
            else -> {}
        }
    }

    private fun get(e: JsonElement, path: List<Any>): JsonElement =
        path.fold(e) { c, k -> if (k is String) (c as JsonObject)[k]!! else (c as JsonArray)[k as Int] }

    private fun replace(e: JsonElement, path: List<Any>, f: (JsonElement) -> JsonElement?): JsonElement {
        if (path.isEmpty()) return f(e) ?: JsonNull
        val k = path.first()
        val rest = path.drop(1)
        return when (e) {
            is JsonObject -> {
                val m = LinkedHashMap(e)
                if (rest.isEmpty()) {
                    val nv = f(m[k as String]!!)
                    if (nv == null) m.remove(k) else m[k] = nv
                } else m[k as String] = replace(m[k]!!, rest, f)
                JsonObject(m)
            }
            is JsonArray -> {
                val l = e.toMutableList()
                val i = k as Int
                if (rest.isEmpty()) {
                    val nv = f(l[i])
                    if (nv == null) l.removeAt(i) else l[i] = nv
                } else l[i] = replace(l[i], rest, f)
                JsonArray(l)
            }
            else -> e
        }
    }

    /** One seeded mutation of [validBytes]. */
    fun mutate(r: Random): ByteArray {
        val base = validBytes
        when (r.nextInt(4)) {
            0 -> { // byte flips
                val b = base.copyOf()
                repeat(1 + r.nextInt(8)) { b[r.nextInt(b.size)] = r.nextInt(256).toByte() }
                return b
            }
            1 -> return base.copyOf(r.nextInt(base.size)) // truncation
        }
        // structural mutation
        var root: JsonElement = Json.parseToJsonElement(String(base))
        repeat(1 + r.nextInt(3)) {
            val all = ArrayList<List<Any>>().also { paths(root, emptyList(), it) }.filter { it.isNotEmpty() }
            val p = all[r.nextInt(all.size)]
            root = when (r.nextInt(7)) {
                0 -> replace(root, p) { null } // delete field / element
                1 -> replace(root, p) { specials[r.nextInt(specials.size)] } // wrong type / huge / NaN
                2 -> replace(root, p) { deepNest(1 + r.nextInt(300)) } // deep nesting
                3 -> replace(root, p) { v -> // duplicate / blow up arrays
                    if (v is JsonArray && v.isNotEmpty()) JsonArray(List(v.size * (2 + r.nextInt(300)).coerceAtMost(20_000 / v.size.coerceAtLeast(1) + 2)) { v[it % v.size] }) else v
                }
                4 -> replace(root, p) { v -> if (v is JsonPrimitive && !v.isString) JsonPrimitive(v.content.toDoubleOrNull()?.times(r.nextInt(1, 1_000_000)) ?: 0.0) else v }
                5 -> replace(root, p) { v -> JsonArray(listOf(v)) } // wrong type: wrap
                else -> replace(root, p) { v -> if (v is JsonObject) JsonObject(v + ("dup" to v)) else v } // unknown / dup
            }
        }
        val text = root.toString()
        return if (r.nextInt(8) == 0) text.replace("0", "9e99").toByteArray() else text.toByteArray()
    }

    fun assertWithinCaps(f: ProfileFile) {
        assertThat(f.tests.size).isAtMost(ProfileImportValidator.MAX_TESTS)
        assertThat(f.tests.sumOf { it.trials?.size ?: 0 }).isAtMost(ProfileImportValidator.MAX_TRIALS)
        fun fin(v: Float) = assertThat(v.isNaN() || v.isInfinite()).isFalse()
        fun lvl(v: Float?) { if (v != null) { fin(v); assertThat(v).isAtLeast(-120f); assertThat(v).isAtMost(0f) } }
        val s = f.profile
        assertThat(s.compStrength).isIn(com.google.common.collect.Range.closed(0f, 1f))
        assertThat(s.gB).isIn(com.google.common.collect.Range.closed(-6f, 12f))
        assertThat(s.gP).isIn(com.google.common.collect.Range.closed(-6f, 6f))
        assertThat(s.gT).isIn(com.google.common.collect.Range.closed(-8f, 8f))
        assertThat(s.name).isNotEmpty()
        assertThat(f.listener.name).isNotEmpty()
        f.headset?.let { h -> assertThat(h.key).isEqualTo(HeadsetKey.of(h.type, h.productName, h.addressSuffix).value) }
        for (t in f.tests) {
            lvl(t.lRef)
            t.fpRate?.let { assertThat(it).isIn(com.google.common.collect.Range.closed(0f, 1f)) }
            t.thresholds.forEach { lvl(it.levelDbfs) }
            t.trials?.forEach { lvl(it.levelDbfs) }
        }
        for (p in f.preferenceTests) {
            assertThat(p.gB).isIn(com.google.common.collect.Range.closed(-6f, 12f))
            assertThat(p.gP).isIn(com.google.common.collect.Range.closed(-6f, 6f))
            assertThat(p.gT).isIn(com.google.common.collect.Range.closed(-8f, 8f))
        }
    }
}

class ImportValidatorFuzzTest {
    @Test fun validBaselineIsAccepted() {
        assertThat(ProfileImportValidator.validate(FuzzSupport.validBytes)).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test fun tenThousandMutationsNeverThrowAndCapsHold() {
        val r = Random(20260101)
        var valid = 0
        repeat(10_000) {
            val bytes = FuzzSupport.mutate(r)
            when (val res = ProfileImportValidator.validate(bytes)) {
                is ValidationResult.Valid -> { valid++; FuzzSupport.assertWithinCaps(res.file) }
                is ValidationResult.Invalid -> assertThat(res.detail.length).isAtMost(400)
            }
        }
        println("fuzz: $valid/10000 mutations still valid")
        assertThat(valid).isGreaterThan(0)
    }

    @Test fun capsAreRejectedNotTruncated() {
        val f = FuzzSupport.validFile()
        val manyTests = f.copy(tests = List(101) { f.tests[0] })
        assertThat((ProfileImportValidator.validate(ProfileJson.encode(manyTests).toByteArray()) as ValidationResult.Invalid).reason.name)
            .isEqualTo("TOO_MANY_TESTS")
        // Compact hand-written trials so 20,001 of them still fit in 2 MB.
        val trials = (0..20_000).joinToString(",") { "{\"seq\":$it,\"ear\":\"LEFT\",\"freqHz\":1000,\"response\":true,\"tMs\":1}" }
        val obj = Json.parseToJsonElement(ProfileJson.encode(f.copy(tests = listOf(f.tests[0])))).jsonObject
        val text = ProfileJson.encode(f.copy(tests = listOf(f.tests[0].copy(trials = emptyList())))).replace("\"trials\":[]", "\"trials\":[$trials]")
        assertThat(obj).isNotNull()
        assertThat((ProfileImportValidator.validate(text.toByteArray()) as ValidationResult.Invalid).reason.name)
            .isEqualTo("TOO_MANY_TRIALS")
    }

    @Test fun clampsAndRecomputesKey() {
        val f = FuzzSupport.validFile()
        val dirty = f.copy(
            headset = f.headset!!.copy(key = "forged"),
            profile = f.profile.copy(compStrength = 5f, gB = 100f, gP = -100f, gT = 99f),
            tests = listOf(f.tests[0].copy(lRef = 50f, fpRate = 3f)),
        )
        val v = ProfileImportValidator.validate(ProfileJson.encode(dirty).toByteArray()) as ValidationResult.Valid
        assertThat(v.file.headset!!.key).isEqualTo(f.headset!!.key)
        assertThat(v.file.profile.compStrength).isEqualTo(1f)
        assertThat(v.file.profile.gB).isEqualTo(12f)
        assertThat(v.file.profile.gP).isEqualTo(-6f)
        assertThat(v.file.profile.gT).isEqualTo(8f)
        assertThat(v.file.tests[0].lRef).isEqualTo(0f)
        assertThat(v.file.tests[0].fpRate).isEqualTo(1f)
    }

    @Test fun nanAndDeepNestingRejected() {
        val text = String(FuzzSupport.validBytes).replace("\"lRef\":-42.0", "\"lRef\":\"NaN\"")
        assertThat(ProfileImportValidator.validate(text.toByteArray())).isInstanceOf(ValidationResult.Invalid::class.java)
        val deep = "{\"schemaVersion\":1,\"x\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertThat((ProfileImportValidator.validate(deep.toByteArray()) as ValidationResult.Invalid).reason.name).isEqualTo("TOO_DEEP")
    }
}

@RunWith(RobolectricTestRunner::class)
class ImportFuzzEndToEndTest {
    @Test fun threeHundredMutationsThroughRoom() = runTest {
        val env = Env(ApplicationProvider.getApplicationContext())
        try {
            val importer = ProfileImporter(env.db)
            val r = Random(777)
            var ok = 0
            repeat(300) {
                val res = importer.import(FuzzSupport.mutate(r), ConflictPolicy.REPLACE_ANY)
                if (res is ImportResult.Success) {
                    ok++
                    val p = env.profiles.get(res.profileId)!!.profile
                    assertThat(p.compStrength).isAtMost(1f)
                    assertThat(p.gB).isAtMost(12f)
                    assertThat(p.gP).isAtLeast(-6f)
                    env.tests.observeForProfileOnce(res.profileId).forEach { t ->
                        assertThat(t.lRef).isIn(com.google.common.collect.Range.closed(-120f, 0f))
                        env.tests.thresholds(t.id).forEach { th ->
                            th.levelDbfs?.let { assertThat(it).isIn(com.google.common.collect.Range.closed(-120f, 0f)) }
                        }
                    }
                }
            }
            println("e2e fuzz: $ok/300 imported")
        } finally {
            env.close()
        }
    }
}

private suspend fun my.umarh.paudio.data.repo.HearingTestRepository.observeForProfileOnce(id: Long) =
    observeForProfile(id).first()
