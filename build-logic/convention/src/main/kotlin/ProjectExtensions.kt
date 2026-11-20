import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal const val PACKAGE_ROOT = "my.umarh.paudio"
internal const val MIN_SDK = 28
internal const val TARGET_SDK = 36
internal const val COMPILE_SDK_MAJOR = 37
internal const val COMPILE_SDK_MINOR = 1
internal val JAVA_VERSION = JavaVersion.VERSION_17
internal val KOTLIN_JVM_TARGET = JvmTarget.JVM_17

/** `:feature:home` -> `my.umarh.paudio.feature.home`. */
internal fun Project.namespaceFromPath(): String =
    PACKAGE_ROOT + "." + path.removePrefix(":").replace(':', '.')

internal fun Project.addLib(configuration: String, alias: String) {
    dependencies.add(configuration, libs.findLibrary(alias).get())
}

internal fun Project.addPlatformLib(configuration: String, alias: String) {
    dependencies.add(configuration, dependencies.platform(libs.findLibrary(alias).get()))
}

/** Shared Android settings for application and library modules (AGP 9 DSL). */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk {
        version = release(COMPILE_SDK_MAJOR) { minorApiLevel = COMPILE_SDK_MINOR }
    }
    android.defaultConfig.minSdk = MIN_SDK
    android.compileOptions.sourceCompatibility = JAVA_VERSION
    android.compileOptions.targetCompatibility = JAVA_VERSION
    android.testOptions.unitTests.isReturnDefaultValues = true
    configureKotlinJvmTarget()
}

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(KOTLIN_JVM_TARGET)
    }
}

/** Test dependencies shared by every module. */
internal fun Project.addCommonTestDependencies() {
    addLib("testImplementation", "junit")
    addLib("testImplementation", "truth")
    addLib("testImplementation", "kotlinx-coroutines-test")
}
