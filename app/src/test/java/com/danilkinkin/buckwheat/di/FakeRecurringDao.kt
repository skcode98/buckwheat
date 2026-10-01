package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeRecurringDao : RecurringDao {
    private val templates = mutableListOf<RecurringTemplate>()

    override fun getAll(): Flow<List<RecurringTemplate>> {
        return flow { emit(templates.toList()) }
    }

    override suspend fun getDueOnDay(day: Int): List<RecurringTemplate> {
        return templates.filter { it.enabled && it.dayOfMonth == day }
    }

    override suspend fun getAllNow(): List<RecurringTemplate> {
        return templates.toList()
    }

    override suspend fun getById(id: String): RecurringTemplate? {
        return templates.firstOrNull { it.id == id }
    }

    // Mirrors the @Upsert on RecurringDao.insert: an existing row is updated in place.
    override suspend fun upsertOne(
        id: String,
        amount: BigDecimal,
        comment: String,
        dayOfMonth: Int,
        enabled: Boolean,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) {
        val template = RecurringTemplate(
            id = id,
            amount = amount,
            comment = comment,
            dayOfMonth = dayOfMonth,
            enabled = enabled,
            familyId = familyId,
            syncSeq = syncSeq,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
        templates.removeAll { it.id == id }
        templates.add(template)
    }

    override suspend fun insert(template: RecurringTemplate) {
        upsertOne(
            id = template.id,
            amount = template.amount,
            comment = template.comment,
            dayOfMonth = template.dayOfMonth,
            enabled = template.enabled,
            familyId = template.familyId,
            syncSeq = template.syncSeq,
            updatedAt = template.updatedAt,
            deletedAt = template.deletedAt,
            version = template.version,
        )
    }

    override suspend fun insertAll(templates: List<RecurringTemplate>) {
        this.templates.addAll(templates)
    }

    override suspend fun update(template: RecurringTemplate) {
        val index = templates.indexOfFirst { it.id == template.id }
        if (index >= 0) {
            templates[index] = template
        }
    }

    override suspend fun delete(template: RecurringTemplate) {
        templates.removeIf { it.id == template.id }
    }

    override suspend fun deleteById(id: String) {
        templates.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        templates.clear()
    }
}
