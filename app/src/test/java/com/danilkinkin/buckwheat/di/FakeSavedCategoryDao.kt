package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.entities.SavedCategory

class FakeSavedCategoryDao : SavedCategoryDao {
    private val categories = mutableListOf<SavedCategory>()

    override fun getAll(): LiveData<List<SavedCategory>> {
        return MutableLiveData(categories)
    }

    override suspend fun getById(id: Int): SavedCategory? {
        return categories.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<SavedCategory> {
        return categories.toList()
    }

    override suspend fun getByName(name: String): SavedCategory? {
        return categories.firstOrNull { it.name == name }
    }

    override suspend fun existsByName(name: String): Boolean {
        return categories.any { it.name == name }
    }

    override suspend fun insert(category: SavedCategory): Long {
        categories.add(category)
        return category.id.toLong()
    }

    override suspend fun insertAll(categories: List<SavedCategory>) {
        this.categories.addAll(categories)
    }

    override suspend fun update(category: SavedCategory) {
        val index = categories.indexOfFirst { it.id == category.id }
        if (index >= 0) {
            categories[index] = category
        }
    }

    override suspend fun deleteById(id: Int) {
        categories.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        categories.clear()
    }
}
