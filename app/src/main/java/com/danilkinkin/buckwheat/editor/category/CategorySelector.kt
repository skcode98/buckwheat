package com.danilkinkin.buckwheat.editor.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.EditorViewModel

// Manual spend-category picker shown in the editor. The user can assign any predefined
// category to the record being added/edited; tapping the selected chip again clears it back
// to auto (null → offline/AI categorization in analytics). A manual pick is persisted on the
// transaction and never overwritten by the AI pass (which only fills null categories).
@Composable
fun CategorySelector(
    editorViewModel: EditorViewModel = hiltViewModel(),
) {
    val selected by editorViewModel.currentCategory.observeAsState(null)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpendCategory.entries.forEach { category ->
            val isSelected = selected == category
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        editorViewModel.currentCategory.value =
                            if (isSelected) null else category
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        text = stringResource(category.labelRes),
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
