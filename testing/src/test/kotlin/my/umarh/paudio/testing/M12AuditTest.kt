package my.umarh.paudio.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * M12 trap audit (DECISIONS §26): static checks over the repository's manifests, backup rules, licence and
 * release rules, for SPEC §2.2 traps that are properties of configuration rather than of runtime behaviour.
 * Plain JVM; paths are relative to the `:testing` module directory (Gradle's test working directory).
 */
class M12AuditTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun file(path: String) = File(root, path).also { assertThat(it.isFile).isTrue() }
    private fun text(path: String) = file(path).readText()

    private val manifests: List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".claude" && it.name != ".git" && it.name != "build-logic" }
        .filter { it.name == "AndroidManifest.xml" && it.path.contains("src/main") }
        .toList()

    @Test
    fun manifestsDeclareNoForbiddenPermissionsAndNoNetwork() {
        assertThat(manifests).isNotEmpty()
        val forbidden = listOf(
            "android.permission.INTERNET", "BLUETOOTH_CONNECT", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "MANAGE_EXTERNAL_STORAGE",
        )
        for (m in manifests) {
            // Only <uses-permission> lines that are not removals count.
            val declared = Regex("""<uses-permission[^>]*>""").findAll(m.readText()).map { it.value }
                .filterNot { it.contains("tools:node=\"remove\"") }
            for (d in declared) forbidden.forEach { assertThat(d).doesNotContain(it) }
        }
    }

    /** Trap 9: no manifest receiver may listen for the (implicit) audio-effect session broadcasts. */
    @Test
    fun noManifestReceiverForAudioEffectSessionBroadcasts() {
        for (m in manifests) {
            assertThat(m.readText()).doesNotContain("OPEN_AUDIO_EFFECT_CONTROL_SESSION")
            assertThat(m.readText()).doesNotContain("CLOSE_AUDIO_EFFECT_CONTROL_SESSION")
        }
    }

    /** Trap 9: the dynamic receiver is registered exported (required for broadcasts from other apps, API 34+). */
    @Test
    fun sessionReceiverIsRegisteredDynamicallyWithReceiverExported() {
        val src = text("audio/effect/src/main/kotlin/my/umarh/paudio/audio/effect/SessionSources.kt")
        assertThat(src).contains("ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)")
        assertThat(src).doesNotContain("RECEIVER_NOT_EXPORTED")
    }

    /** Trap 10: the foreground service type is specialUse, never mediaPlayback (boot start on Android 15+). */
    @Test
    fun foregroundServiceIsSpecialUseNotMediaPlayback() {
        val svc = text("service/src/main/AndroidManifest.xml")
        assertThat(svc).contains("android:foregroundServiceType=\"specialUse\"")
        assertThat(svc).doesNotContain("mediaPlayback")
        assertThat(svc).contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
        assertThat(svc).doesNotContain("FOREGROUND_SERVICE_MEDIA_PLAYBACK")
    }

    /**
     * Trap 10: the boot receiver's intent filter holds exactly one action, BOOT_COMPLETED. It must be
     * exported (system broadcasts need that); BootReceiver re-checks the action at runtime.
     */
    @Test
    fun bootReceiverOnlyHandlesBootCompleted() {
        val svc = text("service/src/main/AndroidManifest.xml")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val receiver = Regex("""<receiver\b[^>]*android:name="\.BootReceiver"[^>]*>(.*?)</receiver>""", RegexOption.DOT_MATCHES_ALL)
            .find(svc)
        assertThat(receiver).isNotNull()
        val actions = Regex("""<action\s+android:name="([^"]+)"""").findAll(receiver!!.groupValues[1])
            .map { it.groupValues[1] }.toList()
        assertThat(actions).containsExactly("android.intent.action.BOOT_COMPLETED")
    }

    /** Trap 16: headsets are identified without the scary Bluetooth permission. */
    @Test
    fun noBluetoothPermissionAnywhere() {
        for (m in manifests) assertThat(m.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), ""))
            .doesNotContain("android.permission.BLUETOOTH")
    }

    /** Trap 17: GPL-3.0-or-later (SPEC §10) is what the repository actually ships. */
    @Test
    fun licenceIsGpl3AndReadmeSaysSo() {
        val lic = text("LICENSE")
        assertThat(lic).contains("GNU GENERAL PUBLIC LICENSE")
        assertThat(lic).contains("Version 3, 29 June 2007")
        assertThat(text("README.md")).contains("GPL-3.0")
    }

    /**
     * Trap 20 (static guard only; the behavioural part is device-only): the test screens suppress UI sound
     * effects, never use assertive live regions, and never call `announceForAccessibility`.
     */
    @Test
    fun hearingTestScreensNeverAnnounceAssertivelyAndSuppressUiSounds() {
        val dir = File(root, "feature/hearingtest/src/main/kotlin")
        val sources = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertThat(sources).isNotEmpty()
        val all = sources.joinToString("\n") { it.readText() }
        assertThat(all).doesNotContain("LiveRegionMode.Assertive")
        assertThat(all).doesNotContain("announceForAccessibility")
        assertThat(all).doesNotContain("sendAccessibilityEvent")
        assertThat(all).contains("SuppressSoundEffects()")
        assertThat(all).contains("isSoundEffectsEnabled = false")
    }

    /** SPEC §6 / docs/PRIVACY.md: backup flags and rules match the privacy statement. */
    @Test
    fun backupPolicyMatchesThePrivacyStatement() {
        val app = text("app/src/main/AndroidManifest.xml")
        assertThat(app).contains("android:allowBackup=\"true\"")
        assertThat(app).contains("@xml/data_extraction_rules")
        assertThat(app).contains("@xml/backup_rules")
        val rules = text("app/src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for (domain in listOf("database", "sharedpref")) {
            assertThat(cloud).contains("<exclude domain=\"$domain\"")
            assertThat(transfer).contains("<include domain=\"$domain\"")
        }
        assertThat(cloud).contains("<exclude domain=\"file\" path=\"datastore/\"")
        assertThat(transfer).contains("<include domain=\"file\" path=\"datastore/\"")
        val legacy = text("app/src/main/res/xml/backup_rules.xml")
        assertThat(legacy).contains("<exclude domain=\"database\"")
        assertThat(legacy).contains("<exclude domain=\"sharedpref\"")
        assertThat(legacy).contains("<exclude domain=\"file\" path=\"datastore/\"")
        val privacy = text("docs/PRIVACY.md")
        assertThat(privacy).contains("`allowBackup` is on")
        assertThat(privacy).contains("exclude the database, SharedPreferences and DataStore from **cloud backup**")
    }

    /** SPEC §6: release builds strip Log.v/d/i; R8 rules keep what reflection and serialization need. */
    @Test
    fun releaseRulesStripVerboseLoggingAndKeepSerializers() {
        val rules = text("app/proguard-rules.pro")
        for (m in listOf("v", "d", "i")) assertThat(rules).contains("public static int $m(...);")
        assertThat(rules).contains("kotlinx.serialization")
        assertThat(rules).contains("\$\$serializer")
    }
}
