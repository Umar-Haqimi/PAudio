package my.umarh.paudio.designsystem

import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import my.umarh.paudio.designsystem.chart.butterflyWingPoints
import my.umarh.paudio.designsystem.hear.MorphShape
import my.umarh.paudio.designsystem.hear.MorphingHearButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun morphShape_outlineDiffersBetweenProgressZeroAndOne() {
        val morph = Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny)
        val size = Size(100f, 100f)
        val a = MorphShape(morph, 0f).createOutline(size, LayoutDirection.Ltr, Density(1f))
        val b = MorphShape(morph, 1f).createOutline(size, LayoutDirection.Ltr, Density(1f))
        assertThat(a).isInstanceOf(Outline.Generic::class.java)
        assertThat(b).isInstanceOf(Outline.Generic::class.java)
        val ca = morph.asCubics(0f).map { it.control0X to it.control0Y }
        val cb = morph.asCubics(1f).map { it.control0X to it.control0Y }
        assertThat(ca).isNotEqualTo(cb)
    }

    @Test
    fun hearButton_pointerDownUp_firesExactlyOnce_andIsLargeEnough() {
        var presses = 0
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                MorphingHearButton(onPress = { presses++ }, stimulusPlaying = false, enabled = true)
            }
        }
        rule.onNodeWithText("I heard it").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("I heard it").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertThat(presses).isEqualTo(1)
    }

    @Test
    fun hearButton_disabled_exposesDisabledSemantics() {
        rule.setContent {
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                MorphingHearButton(onPress = {}, stimulusPlaying = false, enabled = false)
            }
        }
        rule.onNodeWithText("I heard it").assertIsNotEnabled()
    }

    @Test
    fun hearButton_restoresSoundEffectsAfterDispose() {
        var show by mutableStateOf(true)
        var view: android.view.View? = null
        rule.setContent {
            view = LocalView.current
            PAudioTheme(seedColor = Color(0xFF3F6FB5), darkTheme = false) {
                if (show) MorphingHearButton(onPress = {}, stimulusPlaying = false, enabled = true, modifier = Modifier)
            }
        }
        rule.waitForIdle()
        assertThat(view!!.isSoundEffectsEnabled).isFalse()
        show = false
        rule.waitForIdle()
        assertThat(view!!.isSoundEffectsEnabled).isTrue()
    }

    @Test
    fun butterflyGeometry_leftAndRightWingsMirrorAroundCentre() {
        val freqs = listOf(1000, 4000, 14000)
        val hl = listOf(10f, 20f, null)
        val l = butterflyWingPoints(hl, freqs, emptyList(), -1, 100f, 80f, 10f, 210f)
        val r = butterflyWingPoints(hl, freqs, listOf(false, true), 1, 100f, 80f, 10f, 210f)
        assertThat(l).hasSize(2)
        for (i in l.indices) {
            assertThat(l[i].offset.x - 100f).isWithin(1e-4f).of(-(r[i].offset.x - 100f))
            assertThat(l[i].offset.y).isEqualTo(r[i].offset.y)
        }
        assertThat(r[1].censored).isTrue()
        assertThat(l[0].offset.y).isEqualTo(210f)
    }
}
