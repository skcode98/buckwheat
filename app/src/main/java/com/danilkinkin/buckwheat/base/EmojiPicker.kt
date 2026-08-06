package com.danilkinkin.buckwheat.base

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Curated emoji options offered when creating/editing a custom category.
val CATEGORY_EMOJI_OPTIONS = listOf(
    "🍔", "🍕", "☕", "🍎", "🍺", "🚕", "🚌", "🚗",
    "✈️", "🏨", "💊", "🩺", "🎬", "🎮", "🎁", "🍿",
    "🧾", "💡", "📱", "🏠", "💼", "📚", "🎓", "👕",
    "👟", "💄", "🐾", "🌱", "⚽", "💰", "💸", "🏦",
)

// Horizontally scrollable row of emoji chips used to pick the emoji for a custom category.
// The currently selected emoji is highlighted; tapping a chip selects it.
@Composable
fun EmojiPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CATEGORY_EMOJI_OPTIONS.forEach { emoji ->
            val isSelected = emoji == selected
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(emoji) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = emoji,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
