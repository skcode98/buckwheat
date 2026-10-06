package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
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
    val id: String? = null,
)

@HiltViewModel
class TagsManagementViewModel @Inject constructor(
    private val savedTagDao: SavedTagDao,
    private val spendsRepository: SpendsRepository,
    private val syncDirtyMarker: SyncDirtyMarker,
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
                val tag = SavedTag(name = trimmed)
                savedTagDao.insert(tag)
            }
        }
    }

    fun updateTag(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            // copy() keeps the row's sync metadata, which a rebuilt SavedTag would wipe
            val existing = savedTagDao.getById(id) ?: return@launch
            val other = savedTagDao.getByName(trimmed)
            // Don't rename onto an existing tag's name
            if (other == null || other.id == id) {
                savedTagDao.update(existing.copy(name = trimmed))
            }
        }
    }

    fun deleteTag(id: String) {
        viewModelScope.launch {
            val existing = savedTagDao.getById(id) ?: return@launch
            savedTagDao.deleteById(existing.id)
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
