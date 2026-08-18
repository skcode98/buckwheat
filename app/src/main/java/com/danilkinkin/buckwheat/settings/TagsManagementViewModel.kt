package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TagItem(
    val name: String,
    val id: Int? = null,
)

@HiltViewModel
class TagsManagementViewModel @Inject constructor(
    private val savedTagDao: SavedTagDao,
    private val spendsRepository: SpendsRepository,
) : ViewModel() {
    val allTags: StateFlow<List<TagItem>> = combine(
        spendsRepository.getAllTags(),
        savedTagDao.getAll(),
    ) { transactionTags, savedTags ->
        mergeTags(transactionTags, savedTags)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            if (!savedTagDao.existsByName(trimmed)) {
                savedTagDao.insert(SavedTag(name = trimmed))
            }
        }
    }

    fun updateTag(id: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val other = savedTagDao.getByName(trimmed)
            // Don't rename onto an existing tag's name
            if (other == null || other.id == id) {
                savedTagDao.update(SavedTag(name = trimmed).also { it.id = id })
            }
        }
    }

    fun deleteTag(id: Int) {
        viewModelScope.launch {
            savedTagDao.deleteById(id)
        }
    }

    private fun mergeTags(
        transactionTags: List<String>,
        savedTags: List<SavedTag>,
    ): List<TagItem> {
        val savedNames = savedTags.map { it.name }.toSet()
        val fromTransactions = transactionTags
            .filter { it !in savedNames }
            .map { TagItem(name = it) }
        val fromSaved = savedTags.map { TagItem(name = it.name, id = it.id) }
        return fromSaved + fromTransactions
    }
}
