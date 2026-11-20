package my.umarh.paudio.feature.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import my.umarh.paudio.core.audiometry.WhoGrade
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthStringsTest {
    /** Walks up from the working directory until the module's strings file is found (works from module or repo root). */
    private fun stringsFile(): File {
        val relatives = listOf("src/main/res/values/strings.xml", "feature/health/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            relatives.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("strings.xml of :feature:health not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun stringsContainNoForbiddenWords() {
        val xml = stringsFile().readText().lowercase()
        // The normative disclaimer (SPEC section 4) itself says "not a medical diagnosis"; it is the one allowed use.
        val withoutDisclaimer = xml.replace(
            Regex("<string name=\"health_disclaimer\">.*?</string>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        listOf("diagnos", "detected hearing loss", "hearing is damaged").forEach {
            assertThat(withoutDisclaimer).doesNotContain(it)
        }
        // The disclaimer itself must also never claim detection or damage.
        listOf("detected hearing loss", "hearing is damaged").forEach { assertThat(xml).doesNotContain(it) }
    }

    @Test
    fun gradeLabelsAreApproximateAndEstimated() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertThat(ctx.getString(gradeLabelRes(WhoGrade.NORMAL))).isEqualTo("Approximately normal range (estimated)")
        WhoGrade.entries.forEach {
            val s = ctx.getString(gradeLabelRes(it))
            assertThat(s).startsWith("Approximately")
            assertThat(s).endsWith("(estimated)")
        }
    }

    @Test
    fun disclaimerIsExactSpecText() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertThat(ctx.getString(R.string.health_disclaimer)).isEqualTo(
            "PAudio is not a medical device. These results are estimates for music listening only and are not a " +
                "medical diagnosis. Phones and earphones can't be calibrated like clinical equipment, so hearing loss " +
                "that is equal across all frequencies can't be detected. If you notice changes in your hearing, " +
                "ringing, or pain, see a doctor or audiologist.",
        )
    }
}
