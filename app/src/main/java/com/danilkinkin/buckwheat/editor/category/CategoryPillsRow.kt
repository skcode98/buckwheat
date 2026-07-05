package com.danilkinkin.buckwheat.editor.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.SpendsViewModel
import com.danilkinkin.buckwheat.editor.EditorViewModel

@Composable
fun CategoryPillsRow(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
) {
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val currentCategoryId by editorViewModel.currentCategoryId.observeAsState(null)

    if (categories.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { category ->
            val isSelected = currentCategoryId == category.id
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) Color(category.color).copy(alpha = 0.25f)
                    else Color(category.color).copy(alpha = 0.1f),
                onClick = {
                    if (isSelected) {
                        editorViewModel.currentCategoryId.value = null
                        editorViewModel.currentComment.value = ""
                    } else {
                        editorViewModel.currentCategoryId.value = category.id
                        editorViewModel.currentComment.value = category.name
                    }
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(category.color))
                            .padding(5.dp),
                    )
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
