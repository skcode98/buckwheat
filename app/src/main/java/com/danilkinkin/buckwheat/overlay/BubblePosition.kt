package com.danilkinkin.buckwheat.overlay

// Window x for the bubble after a drag: pinned to the nearer screen edge, inset by marginPx.
fun snapX(currentX: Float, screenWidth: Float, bubbleWidth: Float, marginPx: Float): Float =
    if (currentX + bubbleWidth / 2f < screenWidth / 2f) {
        marginPx
    } else {
        (screenWidth - bubbleWidth - marginPx).coerceAtLeast(marginPx)
    }

// Keeps the bubble fully inside the usable screen area (status/nav insets excluded).
fun clampY(currentY: Float, screenHeight: Float, bubbleHeight: Float, topInsetPx: Float, bottomInsetPx: Float): Float {
    val minY = topInsetPx
    val maxY = (screenHeight - bottomInsetPx - bubbleHeight).coerceAtLeast(minY)
    return currentY.coerceIn(minY, maxY)
}

// Downward fling past the velocity threshold dismisses the bubble.
fun isFlingDismiss(velocityY: Float, thresholdPx: Float): Boolean =
    velocityY > thresholdPx // velocityY positive = downward

fun isOverDismissTarget(
    x: Float,
    y: Float,
    targetLeft: Float,
    targetTop: Float,
    targetRight: Float,
    targetBottom: Float,
): Boolean = x in targetLeft..targetRight && y in targetTop..targetBottom
