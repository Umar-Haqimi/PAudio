package my.umarh.paudio.designsystem.hear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.umarh.paudio.designsystem.chart.ChartMath
import my.umarh.paudio.designsystem.chart.rememberReducedMotion

/**
 * Test progress (F7): wavy progress whose wavelength tracks the current test frequency
 * (48 dp at 1 kHz down to 10 dp at 14 kHz, log scale). Wave amplitude eases in while a stimulus plays and
 * back out otherwise. [progress] is 0..1.
 */
@Composable
fun FrequencyWaveProgress(
    progress: Float,
    frequencyHz: Int,
    stimulusPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val amplitude by animateFloatAsState(
        targetValue = if (stimulusPlaying) 1f else 0f,
        animationSpec = if (reduced) snap() else MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "waveAmplitude",
    )
    LinearWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        amplitude = { amplitude },
        wavelength = ChartMath.waveLengthDp(frequencyHz.toFloat()).dp,
        waveSpeed = if (reduced || !stimulusPlaying) 0.dp else 24.dp,
    )
}
