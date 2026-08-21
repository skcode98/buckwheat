package com.danilkinkin.buckwheat.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun swipeAnimatedCardShape(state: DismissState): Shape {
    val dragging by remember {
        derivedStateOf { abs(state.offset.value) > 30 }
    }
    val startCorners by animateDpAsState(
        targetValue = when {
            state.dismissDirection == DismissDirection.StartToEnd &&
                    dragging -> 8.dp
            else -> 22.dp
        },
    )
    val endCorners by animateDpAsState(
        targetValue = when {
            state.dismissDirection == DismissDirection.EndToStart &&
                    dragging -> 8.dp
            else -> 22.dp
        },
    )

    return RoundedCornerShape(
        topStart = startCorners,
        bottomStart = startCorners,
        topEnd = endCorners,
        bottomEnd = endCorners,
    )
}
