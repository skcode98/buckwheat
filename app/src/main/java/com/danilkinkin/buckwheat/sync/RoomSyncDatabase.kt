package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.entities.PendingMutation

data class SyncMeta(
    val memberId: String?,
    val familyId: String?,
    val syncSeq: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val version: Int,
)

interface SyncTableGateway {
    val table: String

    suspend fun loadAll(): List<LocalRecord>

    suspend fun loadById(recordId: String): LocalRecord?

    suspend fun upsert(record: LocalRecord)

    suspend fun remove(recordId: String)
}

class SyncTableBinding<T : Any>(
    override val table: String,
    private val idOf: (T) -> String,
    private val loader: suspend () -> List<T>,
    private val finder: suspend (String) -> T?,
    private val inserter: suspend (T) -> Unit,
    private val remover: suspend (String) -> Unit,
    private val isDirty: suspend (String) -> Boolean,
    private val payloadOf: (T) -> String,
    private val metaOf: (T) -> SyncMeta,
    private val decoder: (LocalRecord) -> T,
) : SyncTableGateway {

    private fun toRecord(entity: T, dirty: Boolean): LocalRecord {
        val meta = metaOf(entity)
        return LocalRecord(
            table = table,
            id = idOf(entity),
            updatedAt = meta.updatedAt,
            version = meta.version,
            deletedAt = meta.deletedAt,
            payload = payloadOf(entity),
            dirty = dirty,
            memberId = meta.memberId,
            familyId = meta.familyId,
            syncSeq = meta.syncSeq,
        )
    }

    override suspend fun loadAll(): List<LocalRecord> = loader().map { toRecord(it, isDirty(idOf(it))) }

    override suspend fun loadById(recordId: String): LocalRecord? {
        val entity = finder(recordId) ?: return null
        return toRecord(entity, dirty = true)
    }

    override suspend fun upsert(record: LocalRecord) {
        val entity = decoder(record)
        remover(record.id)
        inserter(entity)
    }

    override suspend fun remove(recordId: String) = remover(recordId)
}

class RoomSyncDatabase(
    private val gateways: List<SyncTableGateway>,
    private val pendingMutationDao: PendingMutationDao,
    private val syncStateStore: SyncStateStore,
    private val runInTransaction: suspend (block: suspend () -> Unit) -> Unit = { block -> block() },
) : SyncDatabase {

    private fun gateway(table: String): SyncTableGateway? = gateways.firstOrNull { it.table == table }

    override suspend fun readCursor(): Long = syncStateStore.readCursor()

    override suspend fun loadRecords(): List<LocalRecord> = gateways.flatMap { it.loadAll() }

    override suspend fun dirtyRecords(): List<LocalRecord> {
        val queued = pendingMutationDao.getAllNow()
        val stale = mutableListOf<Pair<String, String>>()
        val records = queued.mapNotNull { mutation ->
            val binding = gateway(mutation.table) ?: return@mapNotNull null
            val record = binding.loadById(mutation.recordId)
            if (record != null) {
                record
            } else if (mutation.isDelete) {
                LocalRecord(
                    table = mutation.table,
                    id = mutation.recordId,
                    updatedAt = mutation.queuedAt,
                    version = 1,
                    deletedAt = mutation.queuedAt,
                    payload = "{}",
                    dirty = true,
                    memberId = null,
                )
            } else {
                stale.add(mutation.table to mutation.recordId)
                null
            }
        }
        stale.forEach { (table, recordId) -> pendingMutationDao.deleteQueued(table, listOf(recordId)) }
        return records
    }

    override suspend fun reset() {
        pendingMutationDao.deleteAll()
        syncStateStore.clear()
    }

    override suspend fun apply(apply: SyncApply) {
        val clean = apply.records.filter { !it.dirty }
        val grouped = clean.groupBy { it.table }
        runInTransaction {
            grouped.forEach { (table, records) ->
                val binding = gateway(table) ?: return@forEach
                records.forEach { record ->
                    if (record.deletedAt != null) {
                        binding.remove(record.id)
                    } else {
                        binding.upsert(record)
                    }
                }
            }
            grouped.forEach { (table, records) ->
                pendingMutationDao.deleteQueued(table, records.map { it.id })
            }
        }
        syncStateStore.writeCursor(apply.cursor)
        syncStateStore.replaceConflicts(apply.conflicts)
    }

    override suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long) {
        gateways.forEach { binding ->
            val enrolled = enrolRecords(binding.loadAll(), memberId, familyId, enrolledAt)
            enrolled.forEach { binding.upsert(it) }
            enrolled.forEach { pendingMutationDao.enqueue(PendingMutation(binding.table, it.id, enrolledAt)) }
        }
    }
}
