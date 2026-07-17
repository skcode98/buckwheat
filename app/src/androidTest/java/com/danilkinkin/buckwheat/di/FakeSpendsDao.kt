package com.danilkinkin.buckwheat.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType

class FakeTransactionDao : TransactionDao {
    private val spends = mutableListOf<Transaction>()

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
        return null
    }

    override suspend fun insert(vararg transaction: Transaction) {
        spends.addAll(transaction)
    }

    override suspend fun update(vararg transaction: Transaction) {
    }

    override suspend fun deleteById(uid: Int) {
        spends.removeIf { it.uid == uid }
    }

    override suspend fun deleteAll() {
    }
}