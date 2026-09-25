package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeTransactionDao : TransactionDao {
    val spends = mutableListOf<Transaction>()

    override fun getAll(): Flow<List<Transaction>> {
        return flow { emit(spends.toList()) }
    }

    override fun getAll(type: TransactionType): Flow<List<Transaction>> {
        return flow { emit(spends.toList()) }
    }

    override fun getAll(type: TransactionType, startDate: Long, endDate: Long): Flow<List<Transaction>> {
        return flow { emit(spends.toList()) }
    }

    override fun getAll(startDate: Long, endDate: Long): Flow<List<Transaction>> {
        return flow { emit(spends.toList()) }
    }

    override suspend fun getById(uid: Int): Transaction? {
        return spends.firstOrNull { it.uid == uid }
    }

    override suspend fun getAllNow(): List<Transaction> {
        return spends.toList()
    }

    override suspend fun getAllNow(type: TransactionType, startDate: Long, endDate: Long): List<Transaction> {
        return spends.filter {
            it.type == type && it.date.time in startDate..endDate
        }
    }

    override fun getUncategorizedCount(): Flow<Int> {
        return flow {
            emit(spends.count { it.type == TransactionType.SPENT && it.category.isNullOrBlank() })
        }
    }

    override suspend fun insert(vararg transaction: Transaction) {
        spends.addAll(transaction)
    }

    override suspend fun insertAll(transactions: List<Transaction>) {
        spends.addAll(transactions)
    }

    override suspend fun update(vararg transaction: Transaction) {
        transaction.forEach { incoming ->
            val index = spends.indexOfFirst { it.uid == incoming.uid }
            if (index >= 0) {
                spends[index] = incoming
            }
        }
    }

    override suspend fun deleteById(uid: Int): Int {
        val before = spends.size
        spends.removeIf { it.uid == uid }
        return before - spends.size
    }

    override suspend fun updateCategory(uid: Int, category: String?) {
        val index = spends.indexOfFirst { it.uid == uid }
        if (index >= 0) {
            spends[index] = spends[index].copy(category = category)
        }
    }

    override suspend fun deleteAll() {
        spends.clear()
    }

    override suspend fun deleteAllAndInsert(vararg transaction: Transaction) {
        deleteAll()
        insert(*transaction)
    }
}