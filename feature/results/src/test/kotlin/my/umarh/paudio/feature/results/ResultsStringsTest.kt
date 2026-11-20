package my.umarh.paudio.feature.results

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultsStringsTest {
    /** Walks up from the working directory until the module's strings file is found (works from module or repo root). */
    private fun stringsFile(): File {
        val relatives = listOf("src/main/res/values/strings.xml", "feature/results/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            relatives.map { File(dir, it) }.firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("strings.xml of :feature:results not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun stringsContainNoForbiddenWords() {
        // "does not mean there is no hearing" is the mandated reassurance about censored points (never "no hearing").
        val xml = stringsFile().readText().lowercase().replace("does not mean there is no hearing", "")
        // DECISIONS 17: estimates only; never diagnose, never claim detection, damage or that hearing is fine.
        listOf(
            "diagnos", "detected hearing loss", "hearing is damaged", "your hearing is fine", "hearing is normal",
            "no hearing", "you are deaf", "hearing loss is",
        ).forEach { forbidden -> assertThat(xml).doesNotContain(forbidden) }
    }

    @Test
    fun everyPtaWordingSaysEstimated() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PtaWording.entries.forEach { assertThat(ctx.getString(wordingRes(it))).startsWith("Estimated") }
        assertThat(ctx.getString(R.string.results_pta_value, "12")).contains("(estimated)")
    }

    @Test
    fun exitDialogCopyMatchesTheSpec() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertThat(ctx.getString(R.string.results_exit_title)).isEqualTo("Fine-tune to your taste next?")
        assertThat(ctx.getString(R.string.results_exit_body)).contains("A/B preference test")
        assertThat(ctx.getString(R.string.results_exit_body)).contains("2 minutes")
    }
}
