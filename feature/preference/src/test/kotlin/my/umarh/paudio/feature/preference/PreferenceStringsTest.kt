package my.umarh.paudio.feature.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceStringsTest {
    /** Walks up from the working directory until the module's strings file is found (works from module or repo root). */
    private fun stringsFile(): File {
        val relatives = listOf("src/main/res/values/strings.xml", "feature/preference/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            relatives.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("strings.xml of :feature:preference not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun stringsContainNoForbiddenWords() {
        val xml = stringsFile().readText().lowercase()
        listOf("diagnos", "detected hearing loss", "hearing is damaged", "hearing loss", "damage", "disease", "medical").forEach {
            assertThat(xml).doesNotContain(it)
        }
    }

    @Test
    fun gainFormattingIsLocaleAwareAndSigned() {
        assertThat(formatGain(3f, Locale.US)).isEqualTo("+3")
        assertThat(formatGain(-2.5f, Locale.US)).isEqualTo("−2.5")
        assertThat(formatGain(0f, Locale.US)).isEqualTo("0")
        assertThat(formatGain(0.75f, Locale.US)).isEqualTo("+0.75")
        assertThat(formatGain(2.5f, Locale.GERMANY)).isEqualTo("+2,5")
        assertThat(formatGain(-0.001f, Locale.US)).isEqualTo("0")
    }
}
