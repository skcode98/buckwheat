package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.data.entities.toTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Categorizes every spend in the transactions and archived_transactions tables that has no
// persisted category and saves the assignment to the DB: the offline keyword classifier first
// (instant, deterministic, no AI needed), then the AI model for whatever the keywords couldn't
// place. Persisting means the analytics category breakdown is stable (for current and historical
// periods) and no AI reload is ever required. Runs on an application-scoped background coroutine
// (see CategoryAssignmentScheduler), so it never blocks the caller and survives the screen that
// triggered it.
@Singleton
class CategoryAssigner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: TransactionDao,
    private val budgetPeriodDao: BudgetPeriodDao,
) {
    suspend fun assignToUncategorized() {
        assignTransactions()
        assignArchived()
    }

    private suspend fun assignTransactions() {
        val uncategorized = transactionDao.getAllNow()
            .filter { it.type == TransactionType.SPENT && it.category.isNullOrBlank() }
        if (uncategorized.isEmpty()) return

        val offlineAssigned = uncategorized.mapNotNull { transaction ->
            offlineCategoryOrNull(transaction.comment)
                ?.let { transaction.uid to it.name }
        }
        offlineAssigned.forEach { (uid, category) ->
            transactionDao.updateCategory(uid, category)
        }

        val aiCandidates = uncategorized.filter {
            offlineCategoryOrNull(it.comment) == null
        }
        if (aiCandidates.isEmpty()) return

        val assigned = categorizeSpendsWithAi(context, aiCandidates)
        assigned.forEach { (uid, category) ->
            transactionDao.updateCategory(uid, category.name)
        }
    }

    // Historical spends live in archived_transactions (a separate table with its own uid space),
    // so they are batched separately and persisted via BudgetPeriodDao.
    private suspend fun assignArchived() {
        val uncategorized = budgetPeriodDao.getAllArchivedNow()
            .filter { it.type == TransactionType.SPENT && it.category.isNullOrBlank() }
        if (uncategorized.isEmpty()) return

        val offlineAssigned = uncategorized.mapNotNull { transaction ->
            offlineCategoryOrNull(transaction.comment)
                ?.let { transaction.uid to it.name }
        }
        offlineAssigned.forEach { (uid, category) ->
            budgetPeriodDao.updateCategory(uid, category)
        }

        val aiCandidates = uncategorized.filter {
            offlineCategoryOrNull(it.comment) == null
        }
        if (aiCandidates.isEmpty()) return

        val assigned = categorizeSpendsWithAi(context, aiCandidates.map { it.toTransaction() })
        assigned.forEach { (uid, category) ->
            budgetPeriodDao.updateCategory(uid, category.name)
        }
    }
}
