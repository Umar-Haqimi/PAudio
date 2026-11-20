package my.umarh.paudio.audio.playback

import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.core.model.AudioSource
import my.umarh.paudio.core.preference.DemoClip
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DemoClipSourceTest {
    @Test fun resultsIsWholeClipPreferenceIs12sLoop() {
        assertThat(DemoClipSource.results(DemoClip.ECHOPLEX).loop).isNull()
        val p = DemoClipSource.preference(DemoClip.ECHOPLEX)
        assertThat(p.source).isEqualTo(AudioSource.Asset("demo/echoplex.flac"))
        assertThat(p.loop!!.endInclusive - p.loop!!.start).isEqualTo(12.seconds)
    }
}
