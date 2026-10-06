package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.PendingMutation

interface SyncDatabase {
    suspend fun readCursor(): Long

    /** Local rows with a queued push, tombstone-synthesised when a queued delete outlived its row. */
    suspend fun dirtyRecords(): List<LocalRecord>

    suspend fun loadRecords(): List<LocalRecord>

    suspend fun apply(apply: SyncApply)

    suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long)

    suspend fun reset()
}

class RoomSyncDatabase(
    private val gateways: List<SyncTableGateway>,
    private val pendingMutationDao: PendingMutationDao,
    private val syncStateStore: SyncStateStore,
    private val transactionDao: TransactionDao,
    private val familyTransactionDao: FamilyTransactionDao,
    private val runInTransaction: suspend (block: suspend () -> Unit) -> Unit = { it() },
) : SyncDatabase {

    private fun gateway(table: String): SyncTableGateway? = gateways.firstOrNull { it.table == table }

    override suspend fun readCursor(): Long = syncStateStore.readCursor()

    override suspend fun loadRecords(): List<LocalRecord> = gateways.flatMap { it.loadAll() }

    override suspend fun dirtyRecords(): List<LocalRecord> {
        val queued = pendingMutationDao.getAllNow()
        if (queued.isEmpty()) return emptyList()

        // One scan per table instead of one per queued row: the per-row lookup this replaces made a
        // sync quadratic in the size of the queue.
        val stored = queued
            .groupBy { it.table }
            .flatMap { (table, mutations) ->
                val binding = gateway(table) ?: return@flatMap emptyList()
                binding.loadByIds(mutations.map { it.recordId })
            }
            .associateBy { it.key }

        val records = mutableListOf<LocalRecord>()
        val stale = mutableListOf<Pair<String, String>>()
        queued.forEach { mutation ->
            val record = stored[RecordKey(mutation.table, mutation.recordId)]
            when {
                record != null -> records.add(record.copy(dirty = true))
                mutation.isDelete -> records.add(tombstoneFor(mutation))
                // A queued upsert whose row no longer exists can neither be pushed nor retracted: there
                // is nothing left to send and the server has nothing to undo. Dropping the queue entry is
                // deliberate, so the row stops being re-queued for ever.
                else -> stale.add(mutation.table to mutation.recordId)
            }
        }
        stale.forEach { (table, recordId) -> pendingMutationDao.deleteQueued(table, listOf(recordId)) }
        return records
    }

    /**
     * Builds the delete for a row this device has already removed.
     *
     * Tombstone contract:
     *  - `payload` is the empty object. There is no row left to describe, and the server does not read a
     *    payload for a change that carries `deletedAt`.
     *  - `deletedAt` and `updatedAt` are the moment the delete was queued, so a device with a wrong clock
     *    still moves the record forward instead of resurrecting a stale copy.
     *  - `version` is 1. A hard-deleted row leaves nothing to read its last version from, and the server
     *    resolves a delete by `deletedAt` rather than by comparing versions, so 1 costs nothing. A row
     *    that is still present, and therefore does have a real version, never reaches this function: it
     *    is pushed with its own version above.
     */
    private fun tombstoneFor(mutation: PendingMutation) = LocalRecord(
        table = mutation.table,
        id = mutation.recordId,
        updatedAt = mutation.queuedAt,
        version = 1,
        deletedAt = mutation.queuedAt,
        payload = "{}",
        dirty = true,
        memberId = null,
    )

    override suspend fun apply(apply: SyncApply) {
        val clean = apply.records.filter { !it.dirty }
        val grouped = clean.groupBy { it.table }
        runInTransaction {
            SyncTables.APPLY_ORDER.forEach { table ->
                val records = grouped[table] ?: return@forEach
                val binding = gateway(table) ?: return@forEach
                records.forEach { record ->
                    if (record.deletedAt != null) {
                        binding.remove(record.id)
                    } else {
                        binding.upsert(record)
                    }
                }
            }
            // The queue is settled here, once the writes have committed, and only for a row that has not moved
            // past the version this run pushed. `pending_mutations` is keyed by (table, id) and
            // SyncDirtyMarker re-queues a later edit onto that same key, so deleting by key alone would
            // discard a queue entry for an edit that arrived mid-request and leave that edit unpushed and
            // overwritten. `SyncStampDao` only ever increments the version, so a higher version is exactly
            // that later edit.
            apply.settled.groupBy { it.key.table }.forEach { (table, changes) ->
                val binding = gateway(table)
                val ids = changes.map { it.key.id }
                val current = binding?.loadByIds(ids)?.associateBy { it.id }.orEmpty()
                val settledIds = changes
                    .filter { change ->
                        val row = current[change.key.id]
                        row == null || row.version <= change.version
                    }
                    .map { it.key.id }
                pendingMutationDao.deleteQueued(table, settledIds)
            }
        }

        // The two stores are not one transaction and the order is deliberate. The cursor goes last: a
        // crash between the Room commit and this write leaves the cursor behind, so the next sync re-pulls
        // the same window and rewrites nothing, because an upsert of an identical row is a no-op. Writing
        // the cursor first would skip the window entirely on a crash and lose every row in it. Conflicts
        // go before the cursor for the same reason: they are advisory and re-derived by that re-pull, so
        // writing them early costs nothing and writing them late could lose them.
        syncStateStore.replaceConflicts(apply.conflicts)
        syncStateStore.writeCursor(apply.cursor)
    }

    override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) {
        if (!syncStateStore.isFamilyReHome22Done()) {
            reHomeOwnedRows(memberId)
            syncStateStore.markFamilyReHome22Done()
        }
        runInTransaction {
            gateways.forEach { binding ->
                val enrolled = enrolRecords(binding.loadAll(), memberId, familyId, enrolledAt)
                enrolled.forEach { binding.upsert(it) }
                enrolled.forEach { record ->
                    pendingMutationDao.enqueue(
                        PendingMutation(
                            table = binding.table,
                            recordId = record.id,
                            queuedAt = enrolledAt,
                        )
                    )
                }
            }
        }
    }

    private suspend fun reHomeOwnedRows(memberId: String) {
        val local = transactionDao.getAllNow()
        val orphans = local.filter { it.familyId != null && it.memberId != memberId }
        val attributed = local.filter { it.familyId != null && it.memberId == null }
        if (orphans.isNotEmpty()) {
            familyTransactionDao.insert(
                *orphans.map { row ->
                    FamilyTransaction(
                        id = row.id,
                        type = row.type,
                        value = row.value,
                        date = row.date,
                        comment = row.comment,
                        category = row.category,
                        memberId = row.memberId,
                        syncSeq = row.syncSeq,
                        updatedAt = row.updatedAt,
                        deletedAt = row.deletedAt,
                        version = row.version,
                    )
                }.toTypedArray(),
            )
            orphans.forEach { transactionDao.deleteById(it.id) }
        }
        attributed.forEach { familyTransactionDao.updateMemberId(it.id, memberId) }
        familyTransactionDao.attributeNullMembersTo(memberId)
    }

    override suspend fun reset() {
        runInTransaction {
            pendingMutationDao.deleteAll()
            familyTransactionDao.deleteAll()
        }
        syncStateStore.clear()
    }
}