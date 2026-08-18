package com.danilkinkin.buckwheat.editor.category

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.LocalWindowInsets
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.CheckedRow
import com.danilkinkin.buckwheat.base.LocalBottomSheetScrollState
import com.danilkinkin.buckwheat.editor.EditorViewModel
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.settings.CategoriesManagementViewModel
import com.danilkinkin.buckwheat.settings.CategoryItem

const val CATEGORY_SELECTOR_SHEET = "categorySelector"

// Bottom-sheet list backing the editor's category pill. Mirrors the app's other sheet
// pickers (CurrencyEditor) with CheckedRow rows: Auto, then the built-in predefined
// categories, then the user's saved custom categories. Selecting a row assigns the category
// to the transaction being edited and closes the sheet.
@Composable
fun CategorySelectorSheet(
    editorViewModel: EditorViewModel = hiltViewModel(),
    categoriesViewModel: CategoriesManagementViewModel = hiltViewModel(),
    onClose: () -> Unit = {},
) {
    val localBottomSheetScrollState = LocalBottomSheetScrollState.current

    val selected by editorViewModel.currentCategory.collectAsStateWithLifecycle()
    val categories by categoriesViewModel.allCategories.collectAsStateWithLifecycle()

    val navigationBarHeight = androidx.compose.ui.unit.max(
        LocalWindowInsets.current.calculateBottomPadding(),
        16.dp,
    )

    // Keep the currently selected category visible even when it no longer exists in the
    // managed list (e.g. a custom category deleted after the transaction was categorized).
    val entries = remember(categories, selected) {
        val selectedName = selected
        if (selectedName != null && categories.none { it.name == selectedName }) {
            listOf(CategoryItem(name = selectedName)) + categories
        } else {
            categories
        }
    }

    Surface(Modifier.padding(top = localBottomSheetScrollState.topPadding)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.category_selector_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = navigationBarHeight)
            ) {
                CheckedRow(
                    checked = selected == null,
                    onValueChange = {
                        editorViewModel.currentCategory.value = null
                        onClose()
                    },
                    text = stringResource(R.string.category_auto),
                )
                entries.forEach { item ->
                    CheckedRow(
                        checked = selected == item.name,
                        onValueChange = {
                            editorViewModel.currentCategory.value = item.name
                            onClose()
                        },
                        text = "${SpendCategory.emojiFor(item.name, item.emoji)}  " +
                            categoryDisplayName(item.name),
                    )
                }
            }
        }
    }
}
