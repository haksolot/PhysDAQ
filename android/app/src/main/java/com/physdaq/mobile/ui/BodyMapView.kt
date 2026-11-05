package com.physdaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.physdaq.mobile.nodes.BodyMapLayout
import com.physdaq.mobile.nodes.NodePosition

/** How one position's dot looks: [fill] null means nothing linked there (hollow ring). */
data class BodyDot(val fill: Color?, val selected: Boolean)

/**
 * A front-view figure with one dot per body position, coloured like the rows below
 * it. Tapping a dot calls [onTap] with that position.
 */
@Composable
fun BodyMapView(
    dots: Map<NodePosition, BodyDot>,
    bodyColor: Color,
    emptyColor: Color,
    selectedColor: Color,
    onTap: (NodePosition) -> Unit,
    modifier: Modifier = Modifier
) {
    // The tap handler is installed once; this keeps it calling the latest onTap.
    val currentOnTap by rememberUpdatedState(onTap)
    Canvas(
        modifier
            .height(300.dp)
            .aspectRatio(BodyMapLayout.ASPECT)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    BodyMapLayout.hit(o.x, o.y, size.width.toFloat(), size.height.toFloat(), 22.dp.toPx())
                        ?.let { currentOnTap(it) }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        fun at(p: BodyMapLayout.Point) = Offset(p.x * w, p.y * h)

        // Figure
        drawCircle(bodyColor, BodyMapLayout.HEAD_RADIUS * h, at(BodyMapLayout.headCentre))
        val tl = at(BodyMapLayout.torsoTopLeft)
        val br = at(BodyMapLayout.torsoBottomRight)
        drawRoundRect(bodyColor, tl, Size(br.x - tl.x, br.y - tl.y), CornerRadius(0.04f * h))
        drawLine(bodyColor, Offset(0.5f * w, 0.12f * h), Offset(0.5f * w, 0.16f * h), 0.035f * h)
        val limb = Stroke(width = 0.045f * h, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (line in BodyMapLayout.limbs) {
            val path = Path()
            line.forEachIndexed { i, p ->
                val o = at(p)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, bodyColor, style = limb)
        }

        // Dots
        val r = 9.dp.toPx()
        for ((pos, p) in BodyMapLayout.dots) {
            val c = at(p)
            val d = dots[pos]
            val fill = d?.fill
            if (d?.selected == true) {
                drawCircle(selectedColor, r + 5.dp.toPx(), c, style = Stroke(width = 2.5.dp.toPx()))
            }
            if (fill == null) {
                drawCircle(Color.Black.copy(alpha = 0.35f), r, c)
                drawCircle(emptyColor, r, c, style = Stroke(width = 2.dp.toPx()))
            } else {
                drawCircle(fill, r, c)
            }
        }
    }
}
