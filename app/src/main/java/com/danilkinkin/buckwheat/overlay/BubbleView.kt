package com.danilkinkin.buckwheat.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorMin

val BUBBLE_SIZE_DP = 56

// The floating bubble body. Pure rendering for the current feedback state — drag, snap
// and window placement live in the service. Animated glow (infinite pulse) and spinner
// are plain Compose, which the Glance widget could never do.
@Composable
fun BubbleContent(
    state: BubbleUiState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circleModifier = Modifier
            .size(BUBBLE_SIZE_DP.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = acceptsTap(state.state),
                onClick = onTap,
            )

        when (state.state) {
            BubbleFeedbackState.IDLE -> BubbleCircle(
                modifier = circleModifier,
                background = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "voice input bubble",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            BubbleFeedbackState.LISTENING -> {
                val transition = rememberInfiniteTransition(label = "glow")
                val pulse by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "pulse",
                )
                val glowAlpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 0.5f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "glowAlpha",
                )
                Box(contentAlignment = Alignment.Center) {
                    BubbleCircle(
                        modifier = Modifier
                            .size(BUBBLE_SIZE_DP.dp)
                            .scale(pulse)
                            .alpha(glowAlpha),
                        background = MaterialTheme.colorScheme.primary,
                    ) {}
                    BubbleCircle(
                        modifier = Modifier.size(44.dp),
                        background = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "voice input bubble",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            BubbleFeedbackState.PROCESSING -> BubbleCircle(
                modifier = circleModifier,
                background = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            ) {
                val transition = rememberInfiniteTransition(label = "spin")
                val rot by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900)),
                    label = "rot",
                )
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "voice input bubble",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rot),
                )
            }
            BubbleFeedbackState.ADDED -> BubbleCircle(
                modifier = circleModifier,
                background = colorGood,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "voice input bubble",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            BubbleFeedbackState.ERROR -> BubbleCircle(
                modifier = circleModifier,
                background = colorMin,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "voice input bubble",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        state.caption?.let { caption ->
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = caption },
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BubbleCircle(
    modifier: Modifier,
    background: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
