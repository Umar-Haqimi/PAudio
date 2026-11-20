import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

/** Parses the merged release AndroidManifest.xml and fails on any forbidden `uses-permission` (SPEC §6). */
@CacheableTask
abstract class VerifyPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val forbiddenPermissions: ListProperty<String>

    @TaskAction
    fun verify() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val declared = mutableSetOf<String>()
        listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m").forEach { tag ->
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as org.w3c.dom.Element).getAttributeNS(androidNs, "name")
                if (name.isNotEmpty()) declared += name
            }
        }
        val forbidden = forbiddenPermissions.get().toSet()
        val violations = declared.filter { it in forbidden }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Forbidden permission(s) in merged release manifest: ${violations.sorted().joinToString()}. " +
                    "See SPEC §6. Check which module or library declares them.",
            )
        }
        logger.lifecycle("verifyPermissions OK: ${declared.size} permission(s) declared, none forbidden.")
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
    }
}
