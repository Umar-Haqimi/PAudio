package my.umarh.paudio.feature.onboarding

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class StringsTest {
    private fun stringsFile(): File {
        val relatives = listOf("src/main/res/values/strings.xml", "feature/onboarding/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            relatives.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("strings.xml of :feature:onboarding not found")
    }

    @Test
    fun noForbiddenWords() {
        val xml = stringsFile().readText().lowercase()
        // The normative disclaimer (SPEC section 4 item 4) says "not a medical diagnosis": the only allowed use.
        val withoutDisclaimer = xml.replace(
            Regex("<string name=\"[a-z_]*disclaimer_text\">.*?</string>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        listOf("diagnos", "detected hearing loss", "hearing is damaged").forEach {
            assertThat(withoutDisclaimer).doesNotContain(it)
        }
        listOf("detected hearing loss", "hearing is damaged").forEach { assertThat(xml).doesNotContain(it) }
    }
}
