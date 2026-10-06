package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
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

/** The columns a sync reads and writes on the single [FamilyTransaction] table. */
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
     * The wire table `transactions` is mirrored into the local `family_transactions` table: one
     * binding, because there is exactly one table on the wire after the local-only tables were
     * dropped, and because an id must never be decoded by two different readers.
     */
    fun gateways(familyTransactionDao: FamilyTransactionDao): List<SyncTableGateway> = listOf(
        SyncTableBinding(
            table = SyncTables.TRANSACTIONS,
            loader = { familyTransactionDao.getAllNow() },
            inserter = { familyTransactionDao.insert(it) },
            remover = { familyTransactionDao.deleteById(it) },
            idOf = { it.id },
            isDirty = { recordId ->
                pendingMutationDao.isQueued(SyncTables.TRANSACTIONS, recordId) != 0
            },
            payloadOf = { it.businessPayload().toString() },
            metaOf = {
                SyncMeta(it.updatedAt, it.version, it.deletedAt, it.memberId, null, it.syncSeq)
            },
            decoder = { record ->
                JSONObject(record.payload).readFamilyTransaction(record.id).withSyncMeta(record)
            },
        ),
    )
}
