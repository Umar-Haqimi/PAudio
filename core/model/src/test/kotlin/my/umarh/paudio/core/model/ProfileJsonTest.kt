package my.umarh.paudio.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileJsonTest {
    private fun sample() = ProfileFile(
        listener = ListenerJson("Ada", createdAt = 1_700_000_000_000),
        headset = HeadsetJson(
            key = "BLUETOOTH_A2DP|Buds|AB:CD", displayName = "My buds",
            type = OutputDeviceType.BLUETOOTH_A2DP, productName = "Buds", addressSuffix = "AB:CD",
        ),
        profile = ProfileSettingsJson(
            name = "Ada / Buds", compStrength = 0.37f, gB = 1.25f, gP = -0.5f, gT = 2.125f,
            seedColor = 0xFF3366CC.toInt(), createdAt = 1, updatedAt = 2,
        ),
        tests = listOf(
            HearingTestJson(
                mode = TestMode.QUICK, startedAt = 5, durationMs = 300_000, volIndex = 4, volMax = 15,
                lRef = -61.3f, reliable = true, appVersion = "0.1.0", algoVersion = 1, fpRate = 0.1f,
                thresholds = FrequencyGrid.TEST_FREQUENCIES_HZ.flatMap { f ->
                    Ear.entries.map { ear ->
                        ThresholdJson(ear, f, levelDbfs = if (f == 14000) null else -55.1f - f / 1000f,
                            censored = f == 14000, lowConfidence = f == 12500, trials = 7)
                    }
                },
                deviceSnapshotJson = buildJsonObject { put("ancOn", false) },
                trials = listOf(
                    TrialJson(0, Ear.LEFT, 1000, -50f, isCatch = false, response = true, tMs = 1234),
                    TrialJson(1, Ear.RIGHT, 2000, levelDbfs = null, isCatch = true, response = false, tMs = 2000),
                    TrialJson(2, Ear.LEFT, 3000, -48f, response = false, run = "DESC", tMs = 3000),
                ),
            ),
        ),
        preferenceTests = listOf(
            PreferenceTestJson(9, 1f, 0f, -1f, rounds = buildJsonObject { put("n", 9) }),
        ),
    )

    @Test
    fun roundTripIsLossless() {
        val original = sample()
        assertThat(ProfileJson.decode(ProfileJson.encode(original))).isEqualTo(original)
        assertThat(ProfileJson.decode(ProfileJson.encode(original, pretty = true))).isEqualTo(original)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = ProfileJson.encode(sample()).replaceFirst("{", "{\"futureField\":{\"a\":1},")
        assertThat(ProfileJson.decode(text)).isEqualTo(sample())
    }

    @Test
    fun unknownMajorVersionIsRejected() {
        val text = ProfileJson.encode(sample().copy(schemaVersion = 2))
        assertThrows(UnsupportedSchemaException::class.java) { ProfileJson.decode(text) }
    }

    @Test
    fun malformedInputThrowsSerializationExceptionOnly() {
        listOf("", "[]", "42", "{", "{\"schemaVersion\":1}", "{\"schemaVersion\":\"x\"}").forEach {
            assertThrows(SerializationException::class.java) { ProfileJson.decode(it) }
        }
    }

    @Test
    fun unknownDeviceTypeDecodesToOtherButUnknownTestModeIsRejected() {
        val base = ProfileJson.encode(sample())
        val withType = base.replace("\"BLUETOOTH_A2DP\"", "\"HOLOGRAM\"")
        assertThat(ProfileJson.decode(withType).headset!!.type).isEqualTo(OutputDeviceType.OTHER)
        val badMode = base.replace("\"QUICK\"", "\"WEIRD\"")
        assertThrows(SerializationException::class.java) { ProfileJson.decode(badMode) }
    }

    @Test
    fun missingReliableFlagIsRejected() {
        val text = ProfileJson.encode(sample()).replace("\"reliable\":true,", "")
        assertThrows(SerializationException::class.java) { ProfileJson.decode(text) }
    }
}
