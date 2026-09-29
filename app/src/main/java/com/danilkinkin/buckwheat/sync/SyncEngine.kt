package com.danilkinkin.buckwheat.sync

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SyncDatabase {
    suspend fun readCursor(): Long
    suspend fun dirtyRecords(): List<LocalRecord>
    suspend fun loadRecords(): List<LocalRecord>
    suspend fun apply(apply: SyncApply)
    suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long)
    suspend fun reset()
}

class SyncEngine(
    private val client: SyncClient,
    private val database: SyncDatabase,
    private val sessionProvider: suspend () -> FamilySession?,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock { runSync() }

    private suspend fun runSync(): SyncOutcome {
        val session = sessionProvider() ?: return SyncOutcome.NotEnrolled

        val cursor = database.readCursor()
        val pending = database.dirtyRecords()

        val response = try {
            client.sync(session.token, SyncRequest(cursor = cursor, changes = pending))
        } catch (e: IOException) {
            return SyncOutcome.Failed(e.message ?: "push failed")
        }

        return try {
            val merged = mergePull(
                local = settle(database.loadRecords(), pending, response, session.familyId),
                remote = response.records,
                cursor = response.cursor,
                familyId = session.familyId,
            )

            val everythingPushed = pending.all { it.id in response.accepted }
            val newCursor = if (everythingPushed) merged.cursor else cursor
            val conflicts = response.conflicts + merged.conflicts

            database.apply(
                SyncApply(records = merged.records, cursor = newCursor, conflicts = conflicts)
            )

            SyncOutcome.Synced(cursor = newCursor, conflicts = conflicts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "apply failed")
        }
    }

    private fun settle(
        local: List<LocalRecord>,
        pending: List<LocalRecord>,
        response: SyncResponse,
        familyId: String,
    ): List<LocalRecord> {
        val pushed = pending.map { it.id }.toSet().intersect(response.accepted.toSet())
        val copies = response.records.filter { it.id in pushed }
        return local.map { record ->
            if (record.id !in pushed) return@map record
            val copy = copies.firstOrNull { it.id == record.id } ?: return@map record
            copy.toLocalRecord(familyId = record.familyId ?: familyId)
        }
    }
}
