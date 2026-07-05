package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.SpendsViewModel

const val TAGS_MANAGER_SHEET = "tagsManager"

@Composable
fun TagsManagerSheet(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = maxOf(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )
    val tags by spendsViewModel.tagsWithCount.observeAsState(emptyList())
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val mappings by spendsViewModel.tagCategoryMappings.observeAsState(emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var deletingTag by remember { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectingCategoryForTag by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tags Manager",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                    )
                }
            }
            HorizontalDivider()

            if (tags.isEmpty()) {
                Text(
                    text = "No tags yet. Tags appear as you add comments to transactions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(tags, key = { it.first }) { (tag, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val mapping = mappings.find { it.tagName == tag }
                                if (mapping != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(mapping.categoryColor)),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (categories.isNotEmpty()) {
                                IconButton(
                                    onClick = { selectingCategoryForTag = tag },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_category),
                                        contentDescription = "Category",
                                        modifier = Modifier.size(16.dp),
                                        tint = mappings.find { it.tagName == tag }?.let { Color(it.categoryColor) }
                                            ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editingTag = tag to "" },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = "Edit",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            IconButton(
                                onClick = { deletingTag = tag },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_forever),
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add new tag")
            }
            Spacer(Modifier.height(navigationBarHeight))
        }
    }

    if (showAddDialog) {
        var tagName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add tag") },
            text = {
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text("Tag name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tagName.isNotBlank()) {
                        spendsViewModel.addKnownTag(tagName.trim())
                        showAddDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }

    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text("Delete tag") },
            text = { Text("Delete \"$tag\" from all transactions? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    spendsViewModel.deleteTag(tag)
                    deletingTag = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text("Cancel") }
            },
        )
    }

    editingTag?.let { (oldTag, _) ->
        var newName by remember(oldTag) { mutableStateOf(oldTag) }
        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text("Rename tag") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newName != oldTag) {
                        spendsViewModel.renameTag(oldTag, newName.trim())
                    }
                    editingTag = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { editingTag = null }) { Text("Cancel") }
            },
        )
    }

    selectingCategoryForTag?.let { tagName ->
        val currentMapping = mappings.find { it.tagName == tagName }
        AlertDialog(
            onDismissRequest = { selectingCategoryForTag = null },
            title = { Text("Category for \"$tagName\"") },
            text = {
                Column {
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    spendsViewModel.setTagCategory(tagName, category.id)
                                    selectingCategoryForTag = null
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(category.color)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = category.name,
                                style = if (currentMapping?.categoryId == category.id)
                                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                else
                                    MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (currentMapping != null) {
                        TextButton(
                            onClick = {
                                spendsViewModel.setTagCategory(tagName, null)
                                selectingCategoryForTag = null
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Remove category", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectingCategoryForTag = null }) { Text("Close") }
            },
        )
    }
}
