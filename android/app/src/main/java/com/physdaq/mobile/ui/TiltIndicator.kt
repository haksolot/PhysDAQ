package com.physdaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.physdaq.mobile.dsp.Tilt
import com.physdaq.mobile.ui.theme.Mono
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.hypot

/**
 * A spirit level for one sensor: the bubble sits in the middle when the board is
 * flat, and moves toward the side that is tilted up. Next to it, roll / pitch /
 * yaw in degrees.
 *
 * [read] is polled about 8 times a second so the stream (25 samples a second)
 * never drives Compose state directly.
 */
@Composable
fun TiltIndicator(
    read: () -> Tilt,
    bubbleColor: Color,
    ringColor: Color,
    modifier: Modifier = Modifier
) {
    var tilt by remember { mutableStateOf(Tilt.FLAT) }
    LaunchedEffect(read) {
        while (true) {
            tilt = read()
            delay(120)
        }
    }

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(84.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 - 3f
            drawCircle(ringColor, r, c, style = Stroke(width = 3f))
            drawCircle(ringColor.copy(alpha = 0.5f), r / 2, c, style = Stroke(width = 1.5f))
            drawLine(ringColor.copy(alpha = 0.5f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
            drawLine(ringColor.copy(alpha = 0.5f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)

            // Full deflection at 45 degrees; the bubble stays inside the ring.
            var dx = (tilt.rollDeg / 45.0).coerceIn(-1.0, 1.0) * r * 0.85
            var dy = (tilt.pitchDeg / 45.0).coerceIn(-1.0, 1.0) * r * 0.85
            val len = hypot(dx, dy)
            val limit = r * 0.85
            if (len > limit) { dx *= limit / len; dy *= limit / len }
            // roll up (+Y up) moves the bubble right; pitch (+X down) moves it up.
            drawCircle(bubbleColor, 9f, Offset(c.x + dx.toFloat(), c.y - dy.toFloat()))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono)
            val dim = MaterialTheme.colorScheme.onSurfaceVariant
            Text(String.format(Locale.ROOT, "Roll   %+6.1f deg", tilt.rollDeg), style = mono)
            Text(String.format(Locale.ROOT, "Pitch  %+6.1f deg", tilt.pitchDeg), style = mono)
            Text(String.format(Locale.ROOT, "Yaw    %+6.1f deg*", tilt.yawDeg), style = mono, color = dim)
            Text("*drifts: no compass", style = MaterialTheme.typography.labelSmall, color = dim)
        }
    }
}
