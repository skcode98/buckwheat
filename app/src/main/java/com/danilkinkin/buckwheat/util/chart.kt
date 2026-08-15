package com.danilkinkin.buckwheat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

internal data class SmoothSegment(
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
)

// Catmull-Rom style cubic Bezier segments for a smooth curve through the points.
// A cubic Bezier is a convex combination of its control points, so clamping the control
// points' y into the data's vertical extent keeps the whole curve inside it (otherwise a
// sharp plateau makes the control points overshoot beyond the top/bottom edge).
internal fun smoothSegments(points: List<Offset>): List<SmoothSegment> {
    if (points.size < 2) return emptyList()

    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }

    val extended = buildList {
        add(points[0] - (points[1] - points[0]))
        addAll(points)
        add(points[points.size - 1] + (points[points.size - 1] - points[points.size - 2]))
    }

    return buildList {
        for (i in 0 until extended.size - 3) {
            val p0 = extended[i]
            val p1 = extended[i + 1]
            val p2 = extended[i + 2]
            val p3 = extended[i + 3]

            add(
                SmoothSegment(
                    control1 = Offset(
                        x = p1.x + (p2.x - p0.x) / 6f,
                        y = (p1.y + (p2.y - p0.y) / 6f).coerceIn(minY, maxY),
                    ),
                    control2 = Offset(
                        x = p2.x - (p3.x - p1.x) / 6f,
                        y = (p2.y - (p3.y - p1.y) / 6f).coerceIn(minY, maxY),
                    ),
                    end = p2,
                ),
            )
        }
    }
}

fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path

    path.moveTo(points[0].x, points[0].y)
    for (segment in smoothSegments(points)) {
        path.cubicTo(
            segment.control1.x,
            segment.control1.y,
            segment.control2.x,
            segment.control2.y,
            segment.end.x,
            segment.end.y,
        )
    }
    return path
}
