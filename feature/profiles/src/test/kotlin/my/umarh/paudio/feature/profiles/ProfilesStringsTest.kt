package my.umarh.paudio.feature.profiles

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ProfilesStringsTest {
    private fun stringsFile(): File {
        val relatives = listOf("src/main/res/values/strings.xml", "feature/profiles/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            relatives.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("strings.xml of :feature:profiles not found")
    }

    @Test
    fun noForbiddenWords() {
        val xml = stringsFile().readText().lowercase()
        listOf("diagnos", "detected hearing loss", "hearing is damaged").forEach { assertThat(xml).doesNotContain(it) }
    }
}
