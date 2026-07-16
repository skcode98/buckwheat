package com.danilkinkin.buckwheat.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.entities.SavedTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagsManagementViewModel @Inject constructor(
    private val savedTagDao: SavedTagDao,
) : ViewModel() {
    val tags: LiveData<List<SavedTag>> = savedTagDao.getAll()

    fun addTag(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            savedTagDao.insert(SavedTag(name = name.trim()))
        }
    }

    fun updateTag(id: Int, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            savedTagDao.update(SavedTag(name = name.trim()).also { it.id = id })
        }
    }

    fun deleteTag(id: Int) {
        viewModelScope.launch {
            savedTagDao.deleteById(id)
        }
    }
}
