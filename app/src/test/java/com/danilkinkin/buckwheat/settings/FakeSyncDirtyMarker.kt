package com.danilkinkin.buckwheat.settings

import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.di.FakePendingMutationDao
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker

// A row marked for push, as `table:id`.
data class SyncMark(val table: String, val recordId: String)

// A tombstone queued for a row that existed on the server, with the family metadata it was
// queued from — a row that never reached a family queues nothing, exactly like production.
data class SyncDelete(
    val table: String,
    val recordId: String,
    val familyId: String?,
    val syncSeq: Long,
)

// Stand-in for the production SyncDirtyMarker that records every call so tests can assert which
// table/id pairs a write path enqueues. It also mirrors RoomSyncDirtyMarker's pending_mutations
// bookkeeping through [pendingMutationDao] so tests that already assert on queued rows keep
// working. `queued_at` is pinned so tests never depend on wall-clock time.
class FakeSyncDirtyMarker(
    val pendingMutationDao: FakePendingMutationDao = FakePendingMutationDao(),
) : SyncDirtyMarker {

    val upserts = mutableListOf<SyncMark>()
    val deletes = mutableListOf<SyncDelete>()
    var releases = 0
        private set

    override suspend fun markUpsert(table: String, recordId: String) {
        upserts += SyncMark(table, recordId)
        queue(table, recordId, isDelete = false)
    }

    override suspend fun markUpserts(table: String, recordIds: Collection<String>) {
        recordIds.forEach { markUpsert(table, it) }
    }

    override suspend fun markDelete(
        table: String,
        recordId: String,
        familyId: String?,
        syncSeq: Long,
    ) {
        if (familyId != null && syncSeq > 0L) {
            deletes += SyncDelete(table, recordId, familyId, syncSeq)
            queue(table, recordId, isDelete = true)
        } else {
            pendingMutationDao.deleteQueued(table, listOf(recordId))
        }
    }

    override suspend fun releaseFamily() {
        releases++
    }

    fun upserted(table: String): List<String> =
        upserts.filter { it.table == table }.map { it.recordId }

    fun deleted(table: String): List<SyncDelete> = deletes.filter { it.table == table }

    private suspend fun queue(table: String, recordId: String, isDelete: Boolean) {
        if (pendingMutationDao.mark(table, recordId, QUEUED_AT, isDelete) == 0) {
            pendingMutationDao.enqueue(
                PendingMutation(
                    table = table,
                    recordId = recordId,
                    queuedAt = QUEUED_AT,
                    isDelete = isDelete,
                )
            )
        }
    }

    companion object {
        const val QUEUED_AT = 1_700_000_000_000L
    }
}