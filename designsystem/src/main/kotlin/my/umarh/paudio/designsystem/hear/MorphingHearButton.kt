package my.umarh.paudio.designsystem.hear

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import my.umarh.paudio.designsystem.R
import my.umarh.paudio.designsystem.chart.rememberReducedMotion

/**
 * The big "I heard it" button (F5): Cookie9Sided that pulses softly while [stimulusPlaying] and springs into
 * Sunny while pressed (spring from the theme's motion scheme). Default size 176 dp (>= 160 dp, far above the 48 dp
 * target); callers may override with their own size modifier.
 *
 * Trap #20: this composable NEVER plays a sound or a haptic. [onPress] fires on pointer-down (and on accessibility
 * click); the caller fires haptics AFTER the response was registered. The host view's click sound effects are also
 * disabled while this is composed (restored on dispose).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphingHearButton(
    onPress: () -> Unit,
    stimulusPlaying: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.ds_hear_button_default_label),
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        onDispose { view.isSoundEffectsEnabled = previous }
    }
    val reduced = rememberReducedMotion()
    var pressed by remember { mutableStateOf(false) }
    val morphProgress = animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "hearMorph",
    )
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }

    val pulsing = stimulusPlaying && !reduced && enabled
    val pulseAnim = remember { Animatable(0f) }
    LaunchedEffect(pulsing) {
        if (pulsing) {
            pulseAnim.animateTo(1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
        } else {
            pulseAnim.snapTo(0f)
        }
    }
    val currentOnPress by rememberUpdatedState(onPress)
    val scheme = MaterialTheme.colorScheme
    val container = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.12f)
    val content = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)

    Box(
        modifier = Modifier
            .size(176.dp)
            .then(modifier)
            .graphicsLayer {
                // State reads here are observed by the layer: the outline is re-resolved every frame of the spring.
                val p = if (pulsing) pulseAnim.value else 0f
                val s = 1f + 0.06f * p
                scaleX = s
                scaleY = s
                shape = MorphShape(morph, morphProgress.value)
                clip = true
            }
            .background(container)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick(label = label) {
                    if (enabled) {
                        currentOnPress()
                        true
                    } else {
                        false
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            currentOnPress()
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
