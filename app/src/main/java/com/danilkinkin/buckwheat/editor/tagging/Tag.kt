package com.danilkinkin.buckwheat.editor.tagging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.ui.BuckwheatTheme

@Composable
fun Tag(
    value: String,
    onClick: () -> Unit = {},
    isSelected: Boolean = false,
    categoryColor: Color? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .clickable {
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (categoryColor != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(categoryColor),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                modifier = Modifier.padding(
                    horizontal = if (categoryColor != null) 0.dp else 16.dp,
                    vertical = 0.dp,
                ),
                text = value,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(if (isSelected) 4.dp else 0.dp))
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    BuckwheatTheme {
        Tag("Test tag")
    }
}
