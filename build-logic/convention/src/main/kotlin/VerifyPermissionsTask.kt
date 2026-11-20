import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the merged release AndroidManifest.xml (SPEC §6) and fails if it declares
 *  - any permission on [forbiddenPermissions] (reported with a clear message), or
 *  - any permission that is not on [allowedPermissions] (e.g. one pulled in by a library such as Media3's WAKE_LOCK).
 */
abstract class VerifyPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val forbiddenPermissions: ListProperty<String>

    /** Full permission names that may appear. */
    @get:Input
    abstract val allowedPermissions: ListProperty<String>

    /** The variant's applicationId; `"$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"` is allowed too. */
    @get:Input
    abstract val applicationId: Property<String>

    /** Written on success: the sorted list of declared permissions. Gives the task a proper up-to-date check. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val declared = sortedSetOf<String>()
        listOf("uses-permission", "uses-permission-sdk-23").forEach { tag ->
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as org.w3c.dom.Element).getAttributeNS(androidNs, "name")
                if (name.isNotEmpty()) declared += name
            }
        }
        val forbidden = forbiddenPermissions.get().toSet()
        val allowed = allowedPermissions.get().toSet() + "${applicationId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

        val forbiddenFound = declared.filter { it in forbidden }
        val notAllowed = declared.filter { it !in allowed && it !in forbidden }
        if (forbiddenFound.isNotEmpty() || notAllowed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    if (forbiddenFound.isNotEmpty()) {
                        append("Forbidden permission(s) in merged release manifest: ${forbiddenFound.joinToString()}. ")
                    }
                    if (notAllowed.isNotEmpty()) {
                        append("Permission(s) not on the allow-list: ${notAllowed.joinToString()}. ")
                    }
                    append("See SPEC §6. Check which module or library declares them (app/build/outputs/logs/manifest-merger-release-report.txt); ")
                    append("remove with tools:node=\"remove\" in app/src/main/AndroidManifest.xml.")
                },
            )
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(declared.joinToString("\n", postfix = "\n"))
        }
        logger.lifecycle("verifyPermissions OK: ${declared.size} permission(s) declared, all allowed, none forbidden.")
    }

    companion object {
        val FORBIDDEN = listOf(
            "android.permission.INTERNET",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )

        /** SPEC §6 "Allowed permissions". Add `DUMP` only together with N1. */
        val ALLOWED = listOf(
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.RECORD_AUDIO",
        )
    }
}
