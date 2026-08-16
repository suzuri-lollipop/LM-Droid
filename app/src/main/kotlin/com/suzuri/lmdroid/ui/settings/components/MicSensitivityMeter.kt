package com.suzuri.lmdroid.ui.settings.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A Discord-style mic sensitivity control: a live input-level bar with a draggable threshold
 * marker overlaid on it. The fill turns the active color whenever the live level crosses the
 * marker, previewing exactly what the current threshold would gate through.
 */
@Composable
fun MicSensitivityMeter(
    level: Float,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onThresholdChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val rangeStart = valueRange.start
    val rangeSpan = (valueRange.endInclusive - rangeStart).coerceAtLeast(0.0001f)

    val levelFraction by animateFloatAsState(
        targetValue = ((level - rangeStart) / rangeSpan).coerceIn(0f, 1f),
        label = "micLevelFraction",
    )
    val thresholdFraction = ((threshold - rangeStart) / rangeSpan).coerceIn(0f, 1f)
    val isActive = level >= threshold

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeFillColor = MaterialTheme.colorScheme.primary
    val inactiveFillColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.onSurface

    // Long-lived pointerInput(Unit) coroutine — these must stay fresh across recomposition or
    // the drag would keep calling whatever lambda instance existed when the gesture started.
    val currentOnThresholdChange = rememberUpdatedState(onThresholdChange)
    val currentOnThresholdChangeFinished = rememberUpdatedState(onThresholdChangeFinished)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnThresholdChangeFinished.value() },
                    onDragCancel = { currentOnThresholdChangeFinished.value() },
                ) { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    currentOnThresholdChange.value(rangeStart + fraction * rangeSpan)
                }
            },
    ) {
        val cornerRadius = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = cornerRadius)
        if (levelFraction > 0f) {
            drawRoundRect(
                color = if (isActive) activeFillColor else inactiveFillColor,
                size = Size(size.width * levelFraction, size.height),
                cornerRadius = cornerRadius,
            )
        }
        val markerX = size.width * thresholdFraction
        drawLine(
            color = markerColor,
            start = Offset(markerX, 0f),
            end = Offset(markerX, size.height),
            strokeWidth = 4.dp.toPx(),
        )
        drawCircle(
            color = markerColor,
            radius = 7.dp.toPx(),
            center = Offset(markerX, size.height / 2f),
        )
    }
}
