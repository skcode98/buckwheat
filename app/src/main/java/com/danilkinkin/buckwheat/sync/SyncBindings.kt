package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.FamilyStateDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.PeriodLimitDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.dao.SpendAssignmentDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.data.entities.SpendAssignment
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import org.json.JSONObject

/**
 * One local table, exposed to the sync engine as plain [LocalRecord]s.
 *
 * Deliberately has no single-row `find`. Every lookup a sync performs is a batched [loadByIds], because
 * a per-row lookup that scans the whole table makes a sync quadratic, and a sync reads each table once
 * rather than once per row.
 */
interface SyncTableGateway {
    val table: String

    suspend fun loadAll(): List<LocalRecord>

    suspend fun loadByIds(recordIds: Collection<String>): List<LocalRecord>

    /**
     * A genuine upsert. The insert is conflict-resolving in SQL, so an existing row is updated in place
     * and never deleted first. That is what keeps archived history: deleting a budget period to
     * re-insert it cascades away every archived row that references it.
     */
    suspend fun upsert(record: LocalRecord)

    suspend fun remove(recordId: String)
}

/** The columns a sync reads and writes on every one of the seven tables. */
data class SyncMeta(
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val memberId: String?,
    val familyId: String?,
    val syncSeq: Long,
)

class SyncTableBinding<T : Any>(
    override val table: String,
    private val loader: suspend () -> List<T>,
    private val inserter: suspend (T) -> Unit,
    private val remover: suspend (String) -> Unit,
    private val idOf: (T) -> String,
    private val isDirty: suspend (String) -> Boolean,
    private val payloadOf: (T) -> String,
    private val metaOf: (T) -> SyncMeta,
    private val decoder: (LocalRecord) -> T,
) : SyncTableGateway {

    override suspend fun loadAll(): List<LocalRecord> = loader().map { toRecord(it) }

    override suspend fun loadByIds(recordIds: Collection<String>): List<LocalRecord> {
        if (recordIds.isEmpty()) return emptyList()
        val wanted = recordIds.toHashSet()
        return loader().filter { idOf(it) in wanted }.map { toRecord(it) }
    }

    override suspend fun upsert(record: LocalRecord) {
        inserter(decoder(record))
    }

    override suspend fun remove(recordId: String) {
        remover(recordId)
    }

    private suspend fun toRecord(entity: T): LocalRecord {
        val meta = metaOf(entity)
        val id = idOf(entity)
        return LocalRecord(
            table = table,
            id = id,
            updatedAt = meta.updatedAt,
            version = meta.version,
            deletedAt = meta.deletedAt,
            payload = payloadOf(entity),
            // Read the queue rather than assume: this is the only place a row is called dirty, and a
            // hardcoded `true` here made every pulled row look like a local edit.
            dirty = isDirty(id),
            memberId = meta.memberId,
            familyId = meta.familyId,
            syncSeq = meta.syncSeq,
        )
    }
}

class SyncBindings(
    private val pendingMutationDao: PendingMutationDao,
) {
    /**
     * Bound in [SyncTables.APPLY_ORDER], which is also the order a pull is written in. The order matters
     * because `archived_transactions` has an ON DELETE CASCADE foreign key to `budget_periods`.
     */
    fun gateways(
        transactionDao: TransactionDao,
        budgetPeriodDao: BudgetPeriodDao,
        savedCategoryDao: SavedCategoryDao,
        savedTagDao: SavedTagDao,
        recurringDao: RecurringDao,
        savingsGoalDao: SavingsGoalDao,
        familyStateDao: FamilyStateDao,
        periodLimitDao: PeriodLimitDao,
        spendAssignmentDao: SpendAssignmentDao,
    ): List<SyncTableGateway> = listOf(
        binding(
            table = SyncTables.BUDGET_PERIODS,
            dao = budgetPeriodDao,
            loader = { dao: BudgetPeriodDao -> dao.getAllNow() },
            inserter = { dao: BudgetPeriodDao, record: BudgetPeriod -> dao.insert(record) },
            remover = { dao: BudgetPeriodDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.BUDGET_PERIODS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readBudgetPeriod(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.TRANSACTIONS,
            dao = transactionDao,
            loader = { dao: TransactionDao -> dao.getAllNow() },
            inserter = { dao: TransactionDao, record: Transaction -> dao.insert(record) },
            remover = { dao: TransactionDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.TRANSACTIONS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = {
                SyncMeta(it.updatedAt, it.version, it.deletedAt, it.memberId, it.familyId, it.syncSeq)
            },
            decoder = { record -> JSONObject(record.payload).readTransaction(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.SAVED_CATEGORIES,
            dao = savedCategoryDao,
            loader = { dao: SavedCategoryDao -> dao.getAllNow() },
            inserter = { dao: SavedCategoryDao, record: SavedCategory -> dao.insert(record) },
            remover = { dao: SavedCategoryDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.SAVED_CATEGORIES, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readSavedCategory(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.SAVED_TAGS,
            dao = savedTagDao,
            loader = { dao: SavedTagDao -> dao.getAllNow() },
            inserter = { dao: SavedTagDao, record: SavedTag -> dao.insert(record) },
            remover = { dao: SavedTagDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.SAVED_TAGS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readSavedTag(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.RECURRING_TEMPLATES,
            dao = recurringDao,
            loader = { dao: RecurringDao -> dao.getAllNow() },
            inserter = { dao: RecurringDao, record: RecurringTemplate -> dao.insert(record) },
            remover = { dao: RecurringDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.RECURRING_TEMPLATES, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readRecurringTemplate(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.SAVINGS_GOALS,
            dao = savingsGoalDao,
            loader = { dao: SavingsGoalDao -> dao.getAllNow() },
            inserter = { dao: SavingsGoalDao, record: SavingsGoal -> dao.insert(record) },
            remover = { dao: SavingsGoalDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.SAVINGS_GOALS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readSavingsGoal(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.FAMILY_STATE,
            dao = familyStateDao,
            loader = { dao: FamilyStateDao -> dao.getAllNow() },
            inserter = { dao: FamilyStateDao, record: FamilyState -> dao.upsert(record) },
            remover = { dao: FamilyStateDao, id: String -> dao.deleteByFamilyId(id) },
            idOf = { it.familyId },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.FAMILY_STATE, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readFamilyState(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.PERIOD_LIMITS,
            dao = periodLimitDao,
            loader = { dao: PeriodLimitDao -> dao.getAllNow() },
            inserter = { dao: PeriodLimitDao, record: PeriodLimit -> dao.upsert(record) },
            remover = { dao: PeriodLimitDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.PERIOD_LIMITS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readPeriodLimit(record.id).withSyncMeta(record) },
        ),
        binding(
            table = SyncTables.SPEND_ASSIGNMENTS,
            dao = spendAssignmentDao,
            loader = { dao: SpendAssignmentDao -> dao.getAllNow() },
            inserter = { dao: SpendAssignmentDao, record: SpendAssignment -> dao.upsert(record) },
            remover = { dao: SpendAssignmentDao, id: String -> dao.deleteById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.SPEND_ASSIGNMENTS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, null, it.familyId, it.syncSeq) },
            decoder = { record ->
                JSONObject(record.payload).readSpendAssignment(record.id).withSyncMeta(record)
            },
        ),
        binding(
            table = SyncTables.ARCHIVED_TRANSACTIONS,
            dao = budgetPeriodDao,
            loader = { dao: BudgetPeriodDao -> dao.getAllArchivedNow() },
            inserter = { dao: BudgetPeriodDao, record: ArchivedTransaction ->
                dao.insertArchivedTransactions(listOf(record))
            },
            remover = { dao: BudgetPeriodDao, id: String -> dao.deleteArchivedById(id) },
            idOf = { it.id },
            isDirty = { id -> pendingMutationDao.isQueued(SyncTables.ARCHIVED_TRANSACTIONS, id) != 0 },
            payloadOf = { it.businessPayload().toString() },
            metaOf = {
                SyncMeta(it.updatedAt, it.version, it.deletedAt, it.memberId, it.familyId, it.syncSeq)
            },
            decoder = { record -> JSONObject(record.payload).readArchivedTransaction(record.id).withSyncMeta(record) },
        ),
    )

    private fun <D, T : Any> binding(
        table: String,
        dao: D,
        loader: suspend (D) -> List<T>,
        inserter: suspend (D, T) -> Unit,
        remover: suspend (D, String) -> Unit,
        idOf: (T) -> String,
        isDirty: suspend (String) -> Boolean,
        payloadOf: (T) -> String,
        metaOf: (T) -> SyncMeta,
        decoder: (LocalRecord) -> T,
    ): SyncTableGateway = SyncTableBinding(
        table = table,
        loader = { loader(dao) },
        inserter = { inserter(dao, it) },
        remover = { remover(dao, it) },
        idOf = idOf,
        isDirty = { isDirty(it) },
        payloadOf = payloadOf,
        metaOf = metaOf,
        decoder = decoder,
    )
}