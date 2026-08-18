package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// A category as shown in the Categories Management sheet and the editor picker.
// `id == null` means it is a built-in predefined category (read-only) or a category
// that only exists on transactions and can be saved as a custom one. `emoji` is the
// user-picked emoji for saved custom categories; built-ins resolve their own emoji.
data class CategoryItem(
    val name: String,
    val id: Int? = null,
    val emoji: String = "",
)

@HiltViewModel
class CategoriesManagementViewModel @Inject constructor(
    private val savedCategoryDao: SavedCategoryDao,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    // Built-in categories always first, then saved custom ones, then transaction-only
    // categories that were deleted from the saved list.
    val allCategories: StateFlow<List<CategoryItem>> = combine(
        spendsRepository.getAllCategories(),
        savedCategoryDao.getAll(),
    ) { transactionCategories, savedCategories ->
        mergeCategories(transactionCategories, savedCategories)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String, emoji: String = "") {
        val trimmed = name.trim()
        if (trimmed.isBlank() || SpendCategory.fromStored(trimmed) != null) return
        viewModelScope.launch {
            if (!savedCategoryDao.existsByName(trimmed)) {
                savedCategoryDao.insert(SavedCategory(name = trimmed, emoji = emoji))
            }
        }
    }

    fun updateCategory(id: Int, name: String, emoji: String = "") {
        val trimmed = name.trim()
        if (trimmed.isBlank() || SpendCategory.fromStored(trimmed) != null) return
        viewModelScope.launch {
            val other = savedCategoryDao.getByName(trimmed)
            // Don't rename onto an existing category's name
            if (other == null || other.id == id) {
                savedCategoryDao.update(
                    SavedCategory(name = trimmed, emoji = emoji).also { it.id = id }
                )
            }
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            savedCategoryDao.deleteById(id)
        }
    }

    private fun mergeCategories(
        transactionCategories: List<String>,
        savedCategories: List<SavedCategory>,
    ): List<CategoryItem> {
        val predefined = SpendCategory.entries.map { CategoryItem(name = it.name) }
        val savedNames = savedCategories.map { it.name }.toSet()
        val fromSaved = savedCategories.map {
            CategoryItem(name = it.name, id = it.id, emoji = it.emoji)
        }
        val fromTransactions = transactionCategories
            .filter { SpendCategory.fromStored(it) == null && it !in savedNames }
            .map { CategoryItem(name = it) }
        return predefined + fromSaved + fromTransactions
    }
}
