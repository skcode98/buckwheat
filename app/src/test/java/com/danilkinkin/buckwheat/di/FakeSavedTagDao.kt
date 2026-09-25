package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.entities.SavedTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeSavedTagDao : SavedTagDao {
    private val tags = mutableListOf<SavedTag>()

    override fun getAll(): Flow<List<SavedTag>> {
        return flow { emit(tags.toList()) }
    }

    override suspend fun getById(id: Int): SavedTag? {
        return tags.firstOrNull { it.id == id }
    }

    override suspend fun getAllNow(): List<SavedTag> {
        return tags.toList()
    }

    override suspend fun getByName(name: String): SavedTag? {
        return tags.firstOrNull { it.name == name }
    }

    override suspend fun existsByName(name: String): Boolean {
        return tags.any { it.name == name }
    }

    override suspend fun insert(tag: SavedTag): Long {
        tags.add(tag)
        return tag.id.toLong()
    }

    override suspend fun insertAll(tags: List<SavedTag>) {
        this.tags.addAll(tags)
    }

    override suspend fun update(tag: SavedTag) {
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index >= 0) {
            tags[index] = tag
        }
    }

    override suspend fun deleteById(id: Int) {
        tags.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        tags.clear()
    }
}
