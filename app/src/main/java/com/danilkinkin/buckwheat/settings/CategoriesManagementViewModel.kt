package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// A category as shown in the Categories Management sheet and the editor picker.
// `id == null` means it is a built-in predefined category (read-only) or a category
// that only exists on transactions and can be saved as a custom one.
data class CategoryItem(
    val name: String,
    val id: Int? = null,
)

@HiltViewModel
class CategoriesManagementViewModel @Inject constructor(
    private val savedCategoryDao: SavedCategoryDao,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    // Built-in categories always first, then saved custom ones, then transaction-only
    // categories that were deleted from the saved list.
    val allCategories: LiveData<List<CategoryItem>> = MediatorLiveData<List<CategoryItem>>().apply {
        val transactionCategories = spendsRepository.getAllCategories()
        val savedCategoriesLive = savedCategoryDao.getAll()

        var lastTransactionCategories: List<String> = emptyList()
        var lastSavedCategories: List<SavedCategory> = emptyList()

        addSource(transactionCategories) { categories ->
            lastTransactionCategories = categories
            value = mergeCategories(lastTransactionCategories, lastSavedCategories)
        }
        addSource(savedCategoriesLive) { categories ->
            lastSavedCategories = categories
            value = mergeCategories(lastTransactionCategories, lastSavedCategories)
        }
    }

    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || SpendCategory.fromStored(trimmed) != null) return
        viewModelScope.launch {
            if (!savedCategoryDao.existsByName(trimmed)) {
                savedCategoryDao.insert(SavedCategory(name = trimmed))
            }
        }
    }

    fun updateCategory(id: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || SpendCategory.fromStored(trimmed) != null) return
        viewModelScope.launch {
            val other = savedCategoryDao.getByName(trimmed)
            // Don't rename onto an existing category's name
            if (other == null || other.id == id) {
                savedCategoryDao.update(SavedCategory(name = trimmed).also { it.id = id })
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
        val fromSaved = savedCategories.map { CategoryItem(name = it.name, id = it.id) }
        val fromTransactions = transactionCategories
            .filter { SpendCategory.fromStored(it) == null && it !in savedNames }
            .map { CategoryItem(name = it) }
        return predefined + fromSaved + fromTransactions
    }
}
