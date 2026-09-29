package com.danilkinkin.buckwheat.sync

import java.io.IOException

interface SyncDatabase {
    suspend fun readCursor(): Long
    suspend fun dirtyRecords(): List<LocalRecord>
    suspend fun loadRecords(): List<LocalRecord>
    suspend fun apply(apply: SyncApply)
    suspend fun enrolAll(memberId: String, familyId: String, enrolledAt: Long)
}

class SyncEngine(
    private val client: SyncClient,
    private val database: SyncDatabase,
    private val tokenProvider: suspend () -> String?,
) {
    suspend fun sync(): SyncOutcome {
        val token = tokenProvider() ?: return SyncOutcome.NotEnrolled

        val cursor = database.readCursor()
        val pending = database.dirtyRecords()

        val response = try {
            client.sync(token, SyncRequest(cursor = cursor, changes = pending))
        } catch (e: IOException) {
            return SyncOutcome.Failed(e.message ?: "push failed")
        }

        val merged = mergePull(
            local = settle(database.loadRecords(), pending, response),
            remote = response.records,
            cursor = response.cursor,
        )

        val everythingPushed = pending.all { it.id in response.accepted }
        val newCursor = if (everythingPushed) merged.cursor else cursor
        val conflicts = response.conflicts + merged.conflicts

        database.apply(
            SyncApply(records = merged.records, cursor = newCursor, conflicts = conflicts)
        )

        return SyncOutcome.Synced(cursor = newCursor, conflicts = conflicts)
    }

    private fun settle(
        local: List<LocalRecord>,
        pending: List<LocalRecord>,
        response: SyncResponse,
    ): List<LocalRecord> {
        val pushed = pending.map { it.id }.toSet().intersect(response.accepted.toSet())
        val copies = response.records.filter { it.id in pushed }
        return local.map { record ->
            if (record.id !in pushed) return@map record
            val copy = copies.firstOrNull { it.id == record.id } ?: return@map record
            LocalRecord(
                table = copy.table,
                id = copy.id,
                updatedAt = copy.updatedAt,
                version = copy.version,
                deletedAt = copy.deletedAt,
                payload = copy.payload,
                dirty = false,
                memberId = copy.memberId,
            )
        }
    }
}
