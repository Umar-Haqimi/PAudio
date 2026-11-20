import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Room with KSP and exported schemas (SPEC §2.3: schema export on; migration tests). */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")
        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }
        addLib("implementation", "androidx-room-runtime")
        addLib("implementation", "androidx-room-ktx")
        addLib("ksp", "androidx-room-compiler")
        addLib("testImplementation", "androidx-room-testing")
        addLib("androidTestImplementation", "androidx-room-testing")
    }
}
