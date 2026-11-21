import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Properties

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        // AGP 9 has built-in Kotlin support: do NOT apply org.jetbrains.kotlin.android.
        pluginManager.apply("com.android.application")

        val keystoreProps = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        fun signingValue(propKey: String, envKey: String): String? =
            keystoreProps.getProperty(propKey) ?: System.getenv(envKey)
        val storeFile = signingValue("storeFile", "PAUDIO_KEYSTORE_FILE")
        val hasSigning = storeFile != null &&
            signingValue("storePassword", "PAUDIO_KEYSTORE_PASSWORD") != null &&
            signingValue("keyAlias", "PAUDIO_KEY_ALIAS") != null &&
            signingValue("keyPassword", "PAUDIO_KEY_PASSWORD") != null

        // M12 (DECISIONS §26): with no keystore the release APK stays unsigned. For a local install/smoke test it can
        // be signed with the DEBUG key on request only: -Ppaudio.signReleaseWithDebugKey=true or
        // PAUDIO_SIGN_RELEASE_WITH_DEBUG_KEY=1. Never do this for a distributed build.
        val debugSignRelease = !hasSigning && (
            providers.gradleProperty("paudio.signReleaseWithDebugKey").orNull == "true" ||
                System.getenv("PAUDIO_SIGN_RELEASE_WITH_DEBUG_KEY") == "1"
            )

        addAndroidTestDependencies()
        extensions.configure<ApplicationExtension> {
            namespace = PACKAGE_ROOT
            configureAndroidCommon(this)
            defaultConfig {
                applicationId = PACKAGE_ROOT
                targetSdk = TARGET_SDK
                versionCode = 1
                versionName = "0.1.0"
            }
            // F-Droid friendliness: no dependency metadata blobs (SPEC §5.4).
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            if (hasSigning) {
                signingConfigs {
                    create("release") {
                        this.storeFile = File(storeFile!!).let { if (it.isAbsolute) it else rootProject.file(it) }
                        storePassword = signingValue("storePassword", "PAUDIO_KEYSTORE_PASSWORD")
                        keyAlias = signingValue("keyAlias", "PAUDIO_KEY_ALIAS")
                        keyPassword = signingValue("keyPassword", "PAUDIO_KEY_PASSWORD")
                    }
                }
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    isDebuggable = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    if (hasSigning) {
                        signingConfig = signingConfigs.getByName("release")
                    } else if (debugSignRelease) {
                        signingConfig = signingConfigs.getByName("debug")
                    }
                }
            }
        }

        // SPEC §6: fail the build if a forbidden / non-allow-listed permission appears in a merged RELEASE manifest.
        // One task per release-type variant (safe with flavors) plus the aggregate `verifyPermissions`.
        val verifyAll = tasks.register("verifyPermissions") {
            group = "verification"
            description = "Fails if a merged release manifest declares a forbidden or non-allow-listed permission (SPEC §6)."
        }
        tasks.named("check") { dependsOn(verifyAll) }
        extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
            onVariants(selector().withBuildType("release")) { variant ->
                val capName = variant.name.replaceFirstChar { it.uppercase() }
                val verify = tasks.register<VerifyPermissionsTask>("verify${capName}Permissions") {
                    group = "verification"
                    description = "Verifies permissions of the merged ${variant.name} manifest (SPEC §6)."
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    forbiddenPermissions.set(VerifyPermissionsTask.FORBIDDEN)
                    allowedPermissions.set(VerifyPermissionsTask.ALLOWED)
                    applicationId.set(variant.applicationId)
                    report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
                }
                verifyAll.configure { dependsOn(verify) }
                // Never package a release artifact whose permissions were not verified.
                tasks.matching { it.name == "assemble$capName" || it.name == "bundle$capName" }
                    .configureEach { dependsOn(verify) }
            }
        }
    }
}
