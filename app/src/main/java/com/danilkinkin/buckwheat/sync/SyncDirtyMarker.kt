package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.SyncStampDao
import com.danilkinkin.buckwheat.data.entities.PendingMutation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point every local write path uses to make a row eligible for sync.
 *
 * Two things have to happen together for a row to be pushable:
 *  1. `version` / `updated_at` advance, otherwise the server rejects the push forever
 *     (`decidePush` compares version first and then last-write-wins on `updated_at`).
 *  2. A `pending_mutations` row exists, otherwise the row is never even looked at.
 *
 * Reads the clock through [SyncDirtyClock] so tests can pin `updated_at`.
 */
interface SyncDirtyMarker {

    suspend fun markUpsert(table: String, recordId: String)

    suspend fun markUpserts(table: String, recordIds: Collection<String>)

    /**
     * Queues a tombstone for a row that has already been removed from the local database.
     * Rows that never reached a family (`familyId == null` / `syncSeq == 0`) have nothing on
     * the server to retract, so the queue entry is simply dropped.
     */
    suspend fun markDelete(table: String, recordId: String, familyId: String?, syncSeq: Long)

    /** Drops every trace of family membership from local rows, keeping the rows themselves. */
    suspend fun releaseFamily()
}

fun interface SyncDirtyClock {
    fun now(): Long
}

@Singleton
class RoomSyncDirtyMarker @Inject constructor(
    private val stampDao: SyncStampDao,
    private val pendingMutationDao: PendingMutationDao,
    private val clock: SyncDirtyClock,
) : SyncDirtyMarker {

    override suspend fun markUpsert(table: String, recordId: String) {
        stampDao.stamp(table, recordId, clock.now())
        queue(table, recordId, isDelete = false)
    }

    override suspend fun markUpserts(table: String, recordIds: Collection<String>) {
        recordIds.forEach { markUpsert(table, it) }
    }

    override suspend fun markDelete(table: String, recordId: String, familyId: String?, syncSeq: Long) {
        if (familyId == null || syncSeq <= 0L) {
            pendingMutationDao.deleteQueued(table, listOf(recordId))
            return
        }
        queue(table, recordId, isDelete = true)
    }

    override suspend fun releaseFamily() {
        stampDao.releaseFamily()
    }

    private suspend fun queue(table: String, recordId: String, isDelete: Boolean) {
        val queuedAt = clock.now()
        if (pendingMutationDao.mark(table, recordId, queuedAt, isDelete) == 0) {
            pendingMutationDao.enqueue(
                PendingMutation(
                    table = table,
                    recordId = recordId,
                    queuedAt = queuedAt,
                    isDelete = isDelete,
                )
            )
        }
    }
}

private suspend fun SyncStampDao.stamp(table: String, recordId: String, now: Long) {
    when (table) {
        SyncTables.TRANSACTIONS -> stampTransaction(recordId, now)
        SyncTables.ARCHIVED_TRANSACTIONS -> stampArchivedTransaction(recordId, now)
        SyncTables.BUDGET_PERIODS -> stampBudgetPeriod(recordId, now)
        SyncTables.SAVED_CATEGORIES -> stampSavedCategory(recordId, now)
        SyncTables.SAVED_TAGS -> stampSavedTag(recordId, now)
        SyncTables.RECURRING_TEMPLATES -> stampRecurringTemplate(recordId, now)
        SyncTables.SAVINGS_GOALS -> stampSavingsGoal(recordId, now)
        SyncTables.FAMILY_STATE -> stampFamilyState(recordId, now)
        SyncTables.PERIOD_LIMITS -> stampPeriodLimit(recordId, now)
        SyncTables.SPEND_ASSIGNMENTS -> stampSpendAssignment(recordId, now)
        else -> Unit
    }
}

private suspend fun SyncStampDao.releaseFamily() {
    releaseTransactions()
    releaseArchivedTransactions()
    releaseBudgetPeriods()
    releaseSavedCategories()
    releaseSavedTags()
    releaseRecurringTemplates()
    releaseSavingsGoals()
    releaseFamilyState()
    releasePeriodLimits()
    releaseSpendAssignments()
}