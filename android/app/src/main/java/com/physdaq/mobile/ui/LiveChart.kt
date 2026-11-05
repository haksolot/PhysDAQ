package com.physdaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A scrolling line chart of the last few seconds of a signal, auto-scaled to
 * what is on screen.
 *
 * It does not recompose per sample. A timer bumps a counter about 25 times a
 * second, and only the Canvas's draw step reads that counter, so each tick
 * redraws the chart and nothing else.
 */
@Composable
fun LiveChart(
    title: String,
    buffer: SignalBuffer,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    val frame by produceState(initialValue = 0) {
        while (true) {
            delay(40)
            value = value + 1
        }
    }

    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            @Suppress("UNUSED_VARIABLE")
            val redrawTrigger = frame   // reading it here is what schedules the redraw

            val values = buffer.snapshot()
            if (values.size < 2) return@Canvas

            var lo = values[0]
            var hi = values[0]
            for (v in values) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val span = (hi - lo).coerceAtLeast(1f)
            val pad = size.height * 0.08f
            val drawHeight = size.height - 2 * pad

            val path = Path()
            for (i in values.indices) {
                val x = size.width * i / (buffer.capacity - 1)
                val y = pad + drawHeight * (1f - (values[i] - lo) / span)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 3f))
        }
    }
}
