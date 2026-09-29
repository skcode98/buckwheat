package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import org.json.JSONObject

class SyncBindings(
    private val pendingMutationDao: PendingMutationDao,
) {
    fun gateways(
        transactionDao: TransactionDao,
        budgetPeriodDao: BudgetPeriodDao,
        savedCategoryDao: SavedCategoryDao,
        savedTagDao: SavedTagDao,
        recurringDao: RecurringDao,
        savingsGoalDao: SavingsGoalDao,
    ): List<SyncTableGateway> = listOf(
        transactions(transactionDao),
        archivedTransactions(budgetPeriodDao),
        budgetPeriods(budgetPeriodDao),
        savedCategories(savedCategoryDao),
        savedTags(savedTagDao),
        recurringTemplates(recurringDao),
        savingsGoals(savingsGoalDao),
    )

    private fun metaOf(
        memberId: String?,
        familyId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    ) = SyncMeta(memberId, familyId, syncSeq, updatedAt, deletedAt, version)

    private fun transactions(dao: TransactionDao) = SyncTableBinding<Transaction>(
        table = SyncTables.TRANSACTIONS,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { dao.getById(it) },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.TRANSACTIONS, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(it.memberId, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readTransaction(record.id).withSyncMeta(record)
        },
    )

    private fun archivedTransactions(dao: BudgetPeriodDao) = SyncTableBinding<ArchivedTransaction>(
        table = SyncTables.ARCHIVED_TRANSACTIONS,
        idOf = { it.id },
        loader = { dao.getAllArchivedNow() },
        finder = { id -> dao.getAllArchivedNow().firstOrNull { it.id == id } },
        inserter = { dao.insertArchivedTransactions(listOf(it)) },
        remover = { dao.deleteArchivedById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.ARCHIVED_TRANSACTIONS, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(it.memberId, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readArchivedTransaction(record.id).withSyncMeta(record)
        },
    )

    private fun budgetPeriods(dao: BudgetPeriodDao) = SyncTableBinding<BudgetPeriod>(
        table = SyncTables.BUDGET_PERIODS,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { dao.getById(it) },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.BUDGET_PERIODS, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(null, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readBudgetPeriod(record.id).withSyncMeta(record)
        },
    )

    private fun savedCategories(dao: SavedCategoryDao) = SyncTableBinding<SavedCategory>(
        table = SyncTables.SAVED_CATEGORIES,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { dao.getById(it) },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.SAVED_CATEGORIES, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(null, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readSavedCategory(record.id).withSyncMeta(record)
        },
    )

    private fun savedTags(dao: SavedTagDao) = SyncTableBinding<SavedTag>(
        table = SyncTables.SAVED_TAGS,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { dao.getById(it) },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.SAVED_TAGS, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(null, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readSavedTag(record.id).withSyncMeta(record)
        },
    )

    private fun recurringTemplates(dao: RecurringDao) = SyncTableBinding<RecurringTemplate>(
        table = SyncTables.RECURRING_TEMPLATES,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { id -> dao.getAllNow().firstOrNull { it.id == id } },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.RECURRING_TEMPLATES, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(null, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readRecurringTemplate(record.id).withSyncMeta(record)
        },
    )

    private fun savingsGoals(dao: SavingsGoalDao) = SyncTableBinding<SavingsGoal>(
        table = SyncTables.SAVINGS_GOALS,
        idOf = { it.id },
        loader = { dao.getAllNow() },
        finder = { dao.getById(it) },
        inserter = { dao.insert(it) },
        remover = { dao.deleteById(it) },
        isDirty = { pendingMutationDao.isQueued(SyncTables.SAVINGS_GOALS, it) != 0 },
        payloadOf = { it.businessPayload().toString() },
        metaOf = { metaOf(null, it.familyId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) },
        decoder = { record ->
            JSONObject(record.payload).readSavingsGoal(record.id).withSyncMeta(record)
        },
    )
}
