package my.umarh.paudio.core.preference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GateAndClipTest {
    @Test
    fun gateRequiresBothSidesTwoSeconds() {
        val g = AbRoundGate()
        assertThat(g.canChoose).isFalse()
        g.onPlayed(AbSide.A, 5.0)
        assertThat(g.canChoose).isFalse()
        g.onPlayed(AbSide.B, 1.9)
        assertThat(g.canChoose).isFalse()
        g.onPlayed(AbSide.B, 0.1)
        assertThat(g.canChoose).isTrue()
        g.reset()
        assertThat(g.canChoose).isFalse()
    }

    @Test
    fun gateAccumulatesAcrossSegments() {
        val g = AbRoundGate()
        repeat(4) { g.onPlayed(AbSide.A, 0.5); g.onPlayed(AbSide.B, 0.5) }
        assertThat(g.canChoose).isTrue()
        assertThat(AbRoundGate.LOOP_SECONDS).isEqualTo(12)
    }

    @Test
    fun clipAssignmentAndCycle() {
        assertThat(DemoClip.defaultFor(PreferenceAxis.BASS)).isEqualTo(DemoClip.ECHOPLEX)
        assertThat(DemoClip.defaultFor(PreferenceAxis.PRESENCE)).isEqualTo(DemoClip.DISCIPLINE)
        assertThat(DemoClip.defaultFor(PreferenceAxis.TREBLE)).isEqualTo(DemoClip.GHOSTS_34)
        var c = DemoClip.DISCIPLINE
        val seen = mutableListOf<DemoClip>()
        repeat(4) { seen += c; c = c.next() }
        assertThat(seen).containsExactly(
            DemoClip.DISCIPLINE, DemoClip.ECHOPLEX, DemoClip.GHOSTS_34, DemoClip.DISCIPLINE,
        ).inOrder()
    }

    @Test
    fun assetPaths() {
        assertThat(DemoClip.DISCIPLINE.assetPath).isEqualTo("demo/discipline.flac")
        assertThat(DemoClip.ECHOPLEX.assetPath).isEqualTo("demo/echoplex.flac")
        assertThat(DemoClip.GHOSTS_34.assetPath).isEqualTo("demo/ghosts34.flac")
        assertThat(DemoClip.GHOSTS_34.ltasAssetPath).isEqualTo("demo/ghosts34.ltas.json")
        assertThat(DemoClip.ECHOPLEX.ltasAssetPath).isEqualTo("demo/echoplex.ltas.json")
        assertThat(DemoClip.DISCIPLINE.ltasAssetPath).isEqualTo("demo/discipline.ltas.json")
    }
}
