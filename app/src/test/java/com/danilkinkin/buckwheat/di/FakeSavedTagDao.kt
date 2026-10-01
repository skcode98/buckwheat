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

    override suspend fun getById(id: String): SavedTag? {
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

    override suspend fun upsertOne(
        id: String,
        name: String,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) {
        val tag = SavedTag(
            id = id,
            name = name,
            familyId = familyId,
            syncSeq = syncSeq,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
        val index = tags.indexOfFirst { it.id == id }
        if (index >= 0) tags[index] = tag else tags.add(tag)
    }

    override suspend fun insert(tag: SavedTag) {
        upsertOne(
            id = tag.id,
            name = tag.name,
            familyId = tag.familyId,
            syncSeq = tag.syncSeq,
            updatedAt = tag.updatedAt,
            deletedAt = tag.deletedAt,
            version = tag.version,
        )
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

    override suspend fun deleteById(id: String) {
        tags.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        tags.clear()
    }
}
