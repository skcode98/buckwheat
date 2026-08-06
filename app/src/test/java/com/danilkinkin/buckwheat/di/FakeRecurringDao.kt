package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate

class FakeRecurringDao : RecurringDao {
    private val templates = mutableListOf<RecurringTemplate>()

    override fun getAll(): LiveData<List<RecurringTemplate>> {
        return MutableLiveData(templates)
    }

    override suspend fun getDueOnDay(day: Int): List<RecurringTemplate> {
        return templates.filter { it.enabled && it.dayOfMonth == day }
    }

    override suspend fun getAllNow(): List<RecurringTemplate> {
        return templates.toList()
    }

    override suspend fun insert(template: RecurringTemplate): Long {
        templates.add(template)
        return template.id.toLong()
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

    override suspend fun deleteById(id: Int) {
        templates.removeIf { it.id == id }
    }

    override suspend fun deleteAll() {
        templates.clear()
    }
}
