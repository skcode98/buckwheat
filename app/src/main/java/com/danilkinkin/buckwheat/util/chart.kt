package com.danilkinkin.buckwheat.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path

    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path

    val extended = buildList {
        add(points[0] - (points[1] - points[0]))
        addAll(points)
        add(points[points.size - 1] + (points[points.size - 1] - points[points.size - 2]))
    }

    for (i in 0 until extended.size - 3) {
        val p0 = extended[i]
        val p1 = extended[i + 1]
        val p2 = extended[i + 2]
        val p3 = extended[i + 3]

        val c1 = Offset(
            x = p1.x + (p2.x - p0.x) / 6f,
            y = p1.y + (p2.y - p0.y) / 6f,
        )
        val c2 = Offset(
            x = p2.x - (p3.x - p1.x) / 6f,
            y = p2.y - (p3.y - p1.y) / 6f,
        )
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}
