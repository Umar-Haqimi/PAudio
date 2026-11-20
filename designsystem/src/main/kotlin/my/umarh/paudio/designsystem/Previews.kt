package my.umarh.paudio.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.umarh.paudio.designsystem.chart.Audiogram
import my.umarh.paudio.designsystem.chart.AudiogramPoint
import my.umarh.paudio.designsystem.chart.EqGraph
import my.umarh.paudio.designsystem.chart.HearingButterfly
import my.umarh.paudio.designsystem.chart.PAudioPreviews
import my.umarh.paudio.designsystem.chart.PreviewFrame
import my.umarh.paudio.designsystem.chart.TrendChart
import my.umarh.paudio.designsystem.chart.TrendPoint
import my.umarh.paudio.designsystem.chart.WhoBandLegend
import my.umarh.paudio.designsystem.hear.FrequencyWaveProgress
import my.umarh.paudio.designsystem.hear.MorphingHearButton

internal val PreviewFreqs = listOf(1000, 2000, 3000, 4000, 6000, 8000, 10000, 12500, 14000)

@PAudioPreviews
@Composable
private fun ButterflyPreview() = PreviewFrame {
    HearingButterfly(
        leftHl = listOf(0f, 2f, 3f, 5f, 8f, 12f, 20f, 30f, 40f),
        rightHl = listOf(0f, -2f, 0f, 4f, 6f, 10f, 15f, 25f, 35f),
        frequenciesHz = PreviewFreqs,
        animate = false,
        modifier = Modifier.padding(16.dp),
    )
}

@PAudioPreviews
@Composable
private fun AudiogramPreview() = PreviewFrame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Audiogram(
            rightEar = PreviewFreqs.mapIndexed { i, f -> AudiogramPoint(f, i * 3f, censored = i == 8, lowConfidence = i == 4) },
            leftEar = PreviewFreqs.mapIndexed { i, f -> AudiogramPoint(f, i * 4f + 5f) },
        )
        WhoBandLegend()
    }
}

@PAudioPreviews
@Composable
private fun EqGraphPreview() = PreviewFrame {
    val grid = doubleArrayOf(20.0, 100.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    EqGraph(
        leftDb = doubleArrayOf(0.0, 0.0, 0.5, 0.0, 2.0, 6.0, 10.0, 12.0),
        rightDb = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 4.0, 8.0, 9.0),
        gridHz = grid,
        personalized = true,
        modifier = Modifier.padding(16.dp),
    )
}

@PAudioPreviews
@Composable
private fun TrendPreview() = PreviewFrame {
    TrendChart(
        points = listOf(
            TrendPoint(1_700_000_000_000, 4f, 6f),
            TrendPoint(1_710_000_000_000, 6f, 5f),
            TrendPoint(1_720_000_000_000, 9f, 8f),
        ),
        modifier = Modifier.padding(16.dp),
    )
}

@PAudioPreviews
@Composable
private fun HearPreview() = PreviewFrame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FrequencyWaveProgress(progress = 0.4f, frequencyHz = 4000, stimulusPlaying = true)
        MorphingHearButton(onPress = {}, stimulusPlaying = true, enabled = true)
    }
}
