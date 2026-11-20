package my.umarh.paudio.audio.playback

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import my.umarh.paudio.core.preference.DemoClip
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LtasRepositoryTest {
    @Test fun loadsAllBundledLtasAndCaches() = runTest {
        val repo = LtasRepository(ApplicationProvider.getApplicationContext<android.content.Context>())
        for (c in DemoClip.entries) {
            val l = repo.forClip(c)
            assertThat(l.bandPower.sum()).isWithin(1e-9).of(1.0)
            assertThat(repo.forClip(c)).isSameInstanceAs(l)
        }
        assertThat(repo.pink().bandPower.size).isEqualTo(30)
    }
}
