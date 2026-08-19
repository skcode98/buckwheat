package com.danilkinkin.buckwheat.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.CATEGORY_EMOJI_OPTIONS
import com.danilkinkin.buckwheat.base.EmojiPicker
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.editor.category.categoryDisplayName
import com.danilkinkin.buckwheat.ui.BuckwheatTheme

const val CATEGORIES_MANAGEMENT_SHEET = "categoriesManagement"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesManagementSheet(
    viewModel: CategoriesManagementViewModel = hiltViewModel(),
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current
    val categories by viewModel.allCategories.collectAsStateWithLifecycle()

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    var editingId by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }
    var editingEmoji by remember { mutableStateOf("") }
    var newCategoryText by remember { mutableStateOf("") }
    var newCategoryEmoji by remember { mutableStateOf(CATEGORY_EMOJI_OPTIONS.first()) }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.categories_management_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                EmojiPicker(
                    selected = newCategoryEmoji,
                    onSelect = { newCategoryEmoji = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newCategoryText,
                        onValueChange = { newCategoryText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.categories_management_add_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newCategoryText.isNotBlank()) {
                                    viewModel.addCategory(newCategoryText, newCategoryEmoji)
                                    newCategoryText = ""
                                }
                            }
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (newCategoryText.isNotBlank()) {
                                viewModel.addCategory(newCategoryText, newCategoryEmoji)
                                newCategoryText = ""
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = navigationBarHeight),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                items(categories, key = { "${it.id}_${it.name}" }) { item ->
                    val isBuiltIn = item.id == null && SpendCategory.fromStored(item.name) != null
                    if (item.id != null && editingId == item.id) {
                        EditingCategoryRow(
                            currentName = editingText,
                            currentEmoji = editingEmoji,
                            onNameChange = { editingText = it },
                            onEmojiChange = { editingEmoji = it },
                            onSave = {
                                viewModel.updateCategory(item.id, editingText, editingEmoji)
                                editingId = null
                                editingText = ""
                                editingEmoji = ""
                            },
                            onCancel = {
                                editingId = null
                                editingText = ""
                                editingEmoji = ""
                            },
                        )
                    } else {
                        CategoryItemRow(
                            item = item,
                            isBuiltIn = isBuiltIn,
                            onEdit = if (item.id != null) {
                                {
                                    editingId = item.id
                                    editingText = item.name
                                    editingEmoji = item.emoji
                                }
                            } else null,
                            onSave = if (item.id == null && !isBuiltIn) {
                                { viewModel.addCategory(item.name) }
                            } else null,
                            onDelete = if (item.id != null) {
                                { viewModel.deleteCategory(item.id) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItemRow(
    item: CategoryItem,
    isBuiltIn: Boolean,
    onEdit: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(44.dp)
                .weight(1f),
        ) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "${SpendCategory.emojiFor(item.name, item.emoji)}  " +
                        categoryDisplayName(item.name),
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (isBuiltIn) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.categories_management_builtin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(8.dp))
            if (onSave != null) {
                IconButton(onClick = onSave) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.categories_management_add_hint),
                    )
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.categories_management_edit),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_forever),
                        contentDescription = stringResource(R.string.categories_management_delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditingCategoryRow(
    currentName: String,
    currentEmoji: String,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(56.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EmojiPicker(
            selected = currentEmoji.ifBlank { CATEGORY_EMOJI_OPTIONS.first() },
            onSelect = onEmojiChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = currentName,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSave) {
                Icon(
                    painter = painterResource(R.drawable.ic_apply),
                    contentDescription = stringResource(R.string.apply),
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewCategoriesManagement() {
    BuckwheatTheme {
        CategoriesManagementSheet()
    }
}
