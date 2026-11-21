package my.umarh.paudio.feature.hearingtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Vertical level dial for the Thorough test (SPEC §4 Thorough detail): one detent per dB, drag up = louder, NO
 * numbers anywhere (also not in the semantics: only "louder"/"quieter" custom actions).
 *
 * Haptics (SPEC "haptic tick per detent" vs trap #20 "no haptics during a stimulus"): the pulsed tone loops, so a
 * stimulus is audible most of the time. A detent crossed while a pulse plays is remembered and its single tick is
 * fired when the next silent gap starts ([stimulusPlaying] false: the session plays each gap as its own silent
 * buffer, so the flag drops for the whole gap); ticks never coincide with a pulse. DECISIONS §23, §27.
 */
@Composable
internal fun LevelDial(
    position: Int,
    minPosition: Int,
    maxPosition: Int,
    stimulusPlaying: Boolean,
    onPositionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val currentPosition by rememberUpdatedState(position)
    val currentOnChange by rememberUpdatedState(onPositionChange)
    val playing by rememberUpdatedState(stimulusPlaying)
    var pendingTick by remember { mutableStateOf(false) }
    var lastPosition by remember { mutableStateOf(position) }

    LaunchedEffect(position) {
        if (position != lastPosition) {
            lastPosition = position
            if (playing) pendingTick = true else haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    LaunchedEffect(stimulusPlaying) {
        if (!stimulusPlaying && pendingTick) {
            pendingTick = false
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    val description = stringResource(R.string.hearingtest_dial_description)
    val louder = stringResource(R.string.hearingtest_dial_louder)
    val quieter = stringResource(R.string.hearingtest_dial_quieter)
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val thumb = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .width(96.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(louder) {
                        currentOnChange((currentPosition + 1).coerceIn(minPosition, maxPosition))
                        true
                    },
                    CustomAccessibilityAction(quieter) {
                        currentOnChange((currentPosition - 1).coerceIn(minPosition, maxPosition))
                        true
                    },
                )
            }
            .pointerInput(minPosition, maxPosition) {
                // The thumb follows the finger 1:1: one detent = track length / number of detents.
                val detentPx = ((size.height - 2 * 24.dp.toPx()) / (maxPosition - minPosition).coerceAtLeast(1))
                    .coerceAtLeast(1f)
                var carry = 0f
                // Accumulated locally: several drag events can arrive before the new position has been recomposed
                // into `currentPosition`, and adding to the stale value would lose detents.
                var dragPosition = currentPosition
                detectVerticalDragGestures(
                    onDragStart = {
                        carry = 0f
                        dragPosition = currentPosition
                    },
                ) { change, dragAmount ->
                    change.consume()
                    carry += -dragAmount // finger up = louder
                    val steps = (carry / detentPx).toInt()
                    if (steps != 0) {
                        carry -= steps * detentPx
                        val next = (dragPosition + steps).coerceIn(minPosition, maxPosition)
                        if (next != dragPosition) {
                            dragPosition = next
                            currentOnChange(next)
                        }
                    }
                }
            },
    ) {
        val pad = 24.dp.toPx()
        val cx = size.width / 2f
        val top = pad
        val bottom = size.height - pad
        drawLine(track, Offset(cx, top), Offset(cx, bottom), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        val span = (maxPosition - minPosition).coerceAtLeast(1)
        // Detent marks only (no labels): every 5th mark is longer.
        for (p in minPosition..maxPosition) {
            val y = bottom - (bottom - top) * (p - minPosition) / span
            val long = (p - minPosition) % 5 == 0
            val half = if (long) 16.dp.toPx() else 9.dp.toPx()
            drawLine(tick, Offset(cx - half, y), Offset(cx + half, y), strokeWidth = 1.dp.toPx())
        }
        val ty = bottom - (bottom - top) * (position - minPosition) / span
        val r = 16.dp.toPx()
        drawRoundRect(
            thumb,
            topLeft = Offset(cx - 32.dp.toPx(), ty - r),
            size = Size(64.dp.toPx(), 2 * r),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
    }
}
