package com.danilkinkin.buckwheat.editor.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.EditorViewModel

// Manual spend-category picker shown in the editor. Renders as a single collapsed pill
// ("Auto" by default) so the editor stays clean — the dropdown only opens on tap, and the
// user assigns a category only if they want to. The choice is persisted on the transaction
// and never overwritten by the AI pass (which only fills null categories); picking "Auto"
// clears the manual assignment back to null (offline/AI categorization).
@Composable
fun CategorySelector(
    editorViewModel: EditorViewModel = hiltViewModel(),
) {
    val selected by editorViewModel.currentCategory.observeAsState(null)
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .clickable { menuExpanded = true },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_label),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = selected?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.category_auto),
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = null,
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.category_auto),
                        color = if (selected == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                onClick = {
                    editorViewModel.currentCategory.value = null
                    menuExpanded = false
                },
            )
            SpendCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(category.labelRes),
                            color = if (selected == category) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        editorViewModel.currentCategory.value = category
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
