package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

class FakeTransactionDao : TransactionDao {
    val spends = mutableListOf<Transaction>()

    override fun getAll(): LiveData<List<Transaction>> {
        return MutableLiveData(spends)
    }

    override fun getAll(type: TransactionType): LiveData<List<Transaction>> {
        return MutableLiveData(spends)
    }

    override fun getAll(type: TransactionType, startDate: Long, endDate: Long): LiveData<List<Transaction>> {
        return MutableLiveData(spends)
    }

    override fun getAll(startDate: Long, endDate: Long): LiveData<List<Transaction>> {
        return MutableLiveData(spends)
    }

    override suspend fun getById(uid: Int): Transaction? {
        return spends.firstOrNull { it.uid == uid }
    }

    override suspend fun getAllNow(): List<Transaction> {
        return spends.toList()
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

    override suspend fun deleteById(uid: Int) {
        spends.removeIf { it.uid == uid }
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
