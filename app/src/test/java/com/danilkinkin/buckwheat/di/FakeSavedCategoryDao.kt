package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeSavedCategoryDao : SavedCategoryDao {
    private val categories = mutableListOf<SavedCategory>()

    override fun getAll(): Flow<List<SavedCategory>> {
        return flow { emit(categories.toList()) }
    }

    override suspend fun getById(id: String): SavedCategory? {
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

    override suspend fun upsertOne(
        id: String,
        name: String,
        emoji: String,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) {
        val category = SavedCategory(
            id = id,
            name = name,
            emoji = emoji,
            familyId = familyId,
            syncSeq = syncSeq,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
        val index = categories.indexOfFirst { it.id == id }
        if (index >= 0) categories[index] = category else categories.add(category)
    }

    override suspend fun insert(category: SavedCategory) {
        upsertOne(
            id = category.id,
            name = category.name,
            emoji = category.emoji,
            familyId = category.familyId,
            syncSeq = category.syncSeq,
            updatedAt = category.updatedAt,
            deletedAt = category.deletedAt,
            version = category.version,
        )
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

    override suspend fun deleteById(id: String) {
        categories.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        categories.clear()
    }
}
