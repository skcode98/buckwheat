package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.PendingMutation

class FakePendingMutationDao : PendingMutationDao {
    val mutations = mutableListOf<PendingMutation>()

    override suspend fun getAllNow(): List<PendingMutation> =
        mutations.sortedBy { it.queuedAt }

    override suspend fun enqueue(mutation: PendingMutation) {
        if (mutations.none { it.table == mutation.table && it.recordId == mutation.recordId }) {
            mutations.add(mutation)
        }
    }

    override suspend fun enqueueAll(mutations: List<PendingMutation>) {
        mutations.forEach { enqueue(it) }
    }

    override suspend fun deleteQueued(table: String, recordIds: List<String>) {
        mutations.removeIf { it.table == table && it.recordId in recordIds }
    }

    override suspend fun isQueued(table: String, recordId: String): Int =
        mutations.count { it.table == table && it.recordId == recordId }

    override suspend fun mark(table: String, recordId: String, queuedAt: Long, isDelete: Boolean): Int {
        val index = mutations.indexOfFirst { it.table == table && it.recordId == recordId }
        if (index < 0) return 0
        mutations[index] = mutations[index].copy(queuedAt = queuedAt, isDelete = isDelete)
        return 1
    }

    override suspend fun count(): Int = mutations.size

    override suspend fun deleteAll() {
        mutations.clear()
    }

    fun forTable(table: String): List<PendingMutation> = mutations.filter { it.table == table }

    fun idsFor(table: String): Set<String> = forTable(table).map { it.recordId }.toSet()

    fun tombstones(): List<PendingMutation> = mutations.filter { it.isDelete }
}
