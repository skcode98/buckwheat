package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.danilkinkin.buckwheat.data.entities.Category
import java.math.BigDecimal

const val CATEGORIES_MANAGER_SHEET = "categoriesManager"

@Composable
fun CategoriesManagerSheet(
    spendsViewModel: SpendsViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val navigationBarHeight = maxOf(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )
    val categories by spendsViewModel.categories.observeAsState(emptyList())
    val tagCategoryMappings by spendsViewModel.tagCategoryMappings.observeAsState(emptyList())
    val allTransactions by spendsViewModel.transactions.observeAsState(emptyList())
    val currency = spendsViewModel.currency.observeAsState(initial = com.danilkinkin.buckwheat.data.ExtendCurrency.none())

    var showAddDialog by remember { mutableStateOf(false) }
    var deletingCategory by remember { mutableStateOf<Category?>(null) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var linkingTagsCategory by remember { mutableStateOf<Category?>(null) }

    val mappingsByCategory = remember(tagCategoryMappings) {
        tagCategoryMappings.groupBy { it.categoryId }
    }

    val now = remember { java.time.LocalDate.now() }
    val startOfMonth = now.withDayOfMonth(1)
    val startOfNextMonth = startOfMonth.plusMonths(1)
    val startMs = startOfMonth.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endMs = startOfNextMonth.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    val categoryTotals = remember(allTransactions, startMs, endMs) {
        allTransactions.filter { it.categoryId != null && it.type == com.danilkinkin.buckwheat.data.entities.TransactionType.SPENT }
            .groupBy { it.categoryId!! }
            .mapValues { (_, txs) -> txs.sumOf { it.value.toDouble() }.let { BigDecimal.valueOf(it) } }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { onClose() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.weight(1F))
                Text(
                    text = "Categories",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1F))
                Spacer(Modifier.width(48.dp))
            }

            if (categories.isEmpty()) {
                Text(
                    text = "No categories yet. Add one to group your tags.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(categories, key = { it.id }) { category ->
                        val tags = mappingsByCategory[category.id]?.map { it.tagName } ?: emptyList()
                        val spent = categoryTotals[category.id] ?: BigDecimal.ZERO
                        val limit = category.monthlyLimit
                        val progress = if (limit > BigDecimal.ZERO)
                            (spent / limit).toFloat().coerceIn(0f, 1f) else 0f
                        val overBudget = limit > BigDecimal.ZERO && spent > limit

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable { editingCategory = category }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(category.color)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (limit > BigDecimal.ZERO) {
                                    Text(
                                        text = "${spent.setScale(0).toPlainString()}/${limit.setScale(0).toPlainString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (overBudget) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (limit > BigDecimal.ZERO) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = if (overBudget) MaterialTheme.colorScheme.error
                                        else Color(category.color),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                            if (tags.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = tags.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { linkingTagsCategory = category },
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Text("Tags", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { editingCategory = category },
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = "Edit",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(4.dp))
                                TextButton(
                                    onClick = { deletingCategory = category },
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_forever),
                                        contentDescription = "Delete",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var selectedColor by remember { mutableStateOf(0xFF6200EE) }
        var limitText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Category") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Category name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it },
                        label = { Text("Monthly budget (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ColorPaletteRow(
                        selectedColor = selectedColor,
                        onColorSelected = { selectedColor = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            val limit = limitText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            spendsViewModel.addCategory(name.trim(), selectedColor, limit)
                            showAddDialog = false
                        }
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    deletingCategory?.let { cat ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("Delete Category") },
            text = { Text("Delete \"${cat.name}\"? Tag-category mappings will also be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        spendsViewModel.deleteCategory(cat.id)
                        deletingCategory = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCategory = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    editingCategory?.let { cat ->
        var editName by remember(cat) { mutableStateOf(cat.name) }
        var selectedColor by remember(cat) { mutableStateOf(cat.color) }
        var limitText by remember(cat) { mutableStateOf(
            if (cat.monthlyLimit > BigDecimal.ZERO) cat.monthlyLimit.toPlainString() else ""
        ) }
        val tags = mappingsByCategory[cat.id]?.map { it.tagName } ?: emptyList()

        AlertDialog(
            onDismissRequest = { editingCategory = null },
            title = { Text("Edit Category") },
            text = {
                Column {
                    if (tags.isNotEmpty()) {
                        Text(
                            text = "Tags: ${tags.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Category name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it },
                        label = { Text("Monthly budget") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ColorPaletteRow(
                        selectedColor = selectedColor,
                        onColorSelected = { selectedColor = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editName.isNotBlank()) {
                            val limit = limitText.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            spendsViewModel.updateCategory(cat.id, editName.trim(), selectedColor, limit)
                            editingCategory = null
                        }
                    },
                    enabled = editName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingCategory = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    linkingTagsCategory?.let { cat ->
        val tags by spendsViewModel.tagsWithCount.observeAsState(emptyList())

        AlertDialog(
            onDismissRequest = { linkingTagsCategory = null },
            title = { Text("Tags for \"${cat.name}\"") },
            text = {
                Column {
                    if (tags.isEmpty()) {
                        Text(
                            "No tags yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    } else {
                        tags.forEach { (tag, _) ->
                            val isLinked = mappingsByCategory[cat.id]?.any { it.tagName == tag } == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        if (isLinked) {
                                            spendsViewModel.setTagCategory(tag, null)
                                        } else {
                                            spendsViewModel.setTagCategory(tag, cat.id)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isLinked) Color(cat.color)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                        ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isLinked) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { linkingTagsCategory = null }) { Text("Close") }
            },
        )
    }
}

private val presetColors = listOf(
    0xFF6200EE, 0xFFE91E63, 0xFFF44336, 0xFFFF5722,
    0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF009688,
    0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5, 0xFF9C27B0,
)

@Composable
private fun ColorPaletteRow(
    selectedColor: Long,
    onColorSelected: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presetColors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .clickable { onColorSelected(color) }
                    .then(
                        if (selectedColor == color) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.onSurface,
                            CircleShape,
                        ) else Modifier
                    ),
            )
        }
    }
}
