package com.physdaq.mobile.nodes

/**
 * Where each body position sits on the body-map figure, and which dot a tap hits.
 *
 * The figure is drawn FACING YOU (front view), like a patient standing in front
 * of you, so the person's LEFT wrist is on the RIGHT of the screen.
 *
 * Coordinates are fractions of the drawing area: x of its width, y of its height.
 * The figure is drawn in a box whose width is [ASPECT] times its height.
 */
object BodyMapLayout {

    /** Width / height of the drawing area. */
    const val ASPECT = 0.62f

    data class Point(val x: Float, val y: Float)

    val dots: Map<NodePosition, Point> = mapOf(
        NodePosition.HEAD to Point(0.58f, 0.075f),          // beside the head, at the ear
        NodePosition.CHEST to Point(0.50f, 0.28f),
        NodePosition.WRIST_RIGHT to Point(0.22f, 0.50f),     // person's right = screen left
        NodePosition.FINGER_RIGHT to Point(0.17f, 0.585f),
        NodePosition.WRIST_LEFT to Point(0.78f, 0.50f),
        NodePosition.FINGER_LEFT to Point(0.83f, 0.585f),
        NodePosition.ANKLE_RIGHT to Point(0.42f, 0.92f),
        NodePosition.ANKLE_LEFT to Point(0.58f, 0.92f)
    )

    /** Limbs as polylines (same coordinate system), drawn as thick round-capped strokes. */
    val limbs: List<List<Point>> = listOf(
        listOf(Point(0.36f, 0.18f), Point(0.27f, 0.34f), Point(0.22f, 0.50f), Point(0.19f, 0.56f)),  // right arm
        listOf(Point(0.64f, 0.18f), Point(0.73f, 0.34f), Point(0.78f, 0.50f), Point(0.81f, 0.56f)),  // left arm
        listOf(Point(0.44f, 0.50f), Point(0.43f, 0.72f), Point(0.42f, 0.92f), Point(0.38f, 0.95f)),  // right leg
        listOf(Point(0.56f, 0.50f), Point(0.57f, 0.72f), Point(0.58f, 0.92f), Point(0.62f, 0.95f))   // left leg
    )

    /** Head circle centre (radius is [HEAD_RADIUS] of the height) and torso box corners. */
    val headCentre = Point(0.50f, 0.075f)
    const val HEAD_RADIUS = 0.055f
    val torsoTopLeft = Point(0.35f, 0.155f)
    val torsoBottomRight = Point(0.65f, 0.52f)

    /**
     * The dot under a tap at ([x], [y]) pixels in a [width] x [height] area, or null
     * if no dot centre is within [radius] pixels. The nearest dot wins.
     */
    fun hit(x: Float, y: Float, width: Float, height: Float, radius: Float): NodePosition? {
        var best: NodePosition? = null
        var bestD2 = radius * radius
        for ((pos, p) in dots) {
            val dx = p.x * width - x
            val dy = p.y * height - y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestD2) {
                bestD2 = d2
                best = pos
            }
        }
        return best
    }
}
