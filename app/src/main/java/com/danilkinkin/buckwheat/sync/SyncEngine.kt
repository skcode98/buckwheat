package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException

/** The server caps a pull window, so a first sync can need more than one round trip. */
private const val MAX_SYNC_PAGES = 200

class SyncEngine(
    private val client: SyncClient,
    private val database: SyncDatabase,
    private val sessionProvider: suspend () -> FamilySession?,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock { runSync() }

    private suspend fun runSync(): SyncOutcome {
        val session = sessionProvider() ?: return SyncOutcome.NotEnrolled

        val startCursor = database.readCursor()
        val pending = database.dirtyRecords()

        var cursor = startCursor
        var changes = pending
        var accepted = emptyList<RecordKey>()
        var records = emptyList<WireRecord>()
        var conflicts = emptyList<ConflictNotice>()
        var page = 0

        while (page < MAX_SYNC_PAGES) {
            page++
            val response = try {
                client.sync(session.token, SyncRequest(cursor = cursor, changes = changes))
            } catch (e: IOException) {
                return SyncOutcome.Failed(e.message ?: "push failed")
            }
            if (page == 1) accepted = response.accepted
            records = records + response.records
            conflicts = conflicts + response.conflicts
            // The cursor always advances to what the server actually returned, floored at what this
            // device already had. Holding it back until every push was accepted let one permanently
            // refused row re-pull the same window on every sync and re-report the same conflict forever,
            // with no way for the user to resolve it.
            cursor = maxOf(cursor, response.cursor)
            changes = emptyList()
            if (!response.hasMore) break
        }

        return try {
            val settled = settle(
                local = database.loadRecords(),
                pushed = pending,
                serverCopies = records,
                familyId = session.familyId,
            )
            val merged = mergePull(
                local = settled.records,
                remote = records,
                cursor = cursor,
                familyId = session.familyId,
            )

            val notices = mergeNotices(
                server = conflicts,
                local = merged.conflicts,
                unreported = unreportedRefusals(pushed = pending, accepted = accepted, reported = conflicts),
            )
            database.apply(
                SyncApply(
                    records = merged.records,
                    cursor = merged.cursor,
                    conflicts = notices,
                    // Only pushes the server answered AND that the row still holds at the version
                    // they were sent at. A row edited again mid-request keeps its queue entry, so
                    // that edit is pushed by the next run instead of being overwritten and lost.
                    settled = settled.settled,
                )
            )

            SyncOutcome.Synced(cursor = merged.cursor, conflicts = notices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncPayloadException) {
            // A payload the server sent cannot be turned into a row. Naming it is the difference between
            // "a member's income turned into a spend" and an anonymous failure.
            SyncOutcome.Failed("bad payload: ${e.message}")
        } catch (e: JSONException) {
            SyncOutcome.Failed("bad payload: ${e.message}")
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "apply failed")
        }
    }

    private data class Settled(
        val records: List<LocalRecord>,
        val settled: List<SettledChange>,
    )

    /**
     * Resolves every change this run pushed, so nothing that was pushed stays dirty afterwards:
     *  - the server's copy, accepted or refused, is authoritative, so take it;
     *  - with no copy in the pull window the change is still settled: it has a decision either way, and
     *    leaving it queued is what pinned the cursor and re-reported the same conflict on every sync;
     *  - a tombstone has no local row at all, so it is appended here. Without that nothing downstream
     *    ever sees it, and its queue entry could never be cleared;
     *  - a row whose version has moved past the version that was pushed was edited again while this
     *    run was in flight. The server answered an older change, so its copy no longer describes the
     *    row: the local edit is kept, stays dirty, and its queue entry is deliberately left in place.
     *    `SyncStampDao` only ever increments a version, so a higher one can only mean a later write.
     */
    private fun settle(
        local: List<LocalRecord>,
        pushed: List<LocalRecord>,
        serverCopies: List<WireRecord>,
        familyId: String?,
    ): Settled {
        val copies = serverCopies.associateBy { it.key }
        val pushedByKey = pushed.associateBy { it.key }
        val settled = mutableListOf<SettledChange>()

        fun resolve(record: LocalRecord): LocalRecord {
            val sent = pushedByKey[record.key] ?: return record
            // Strictly greater, not merely different: `SyncStampDao` only ever does `version + 1`, so
            // a higher version can only mean the row was written again after this run read it. Any
            // other value is the same change and the server's copy does describe it.
            if (record.version > sent.version) return record.copy(dirty = true)
            val copy = copies[record.key]
            settled.add(SettledChange(key = record.key, version = sent.version))
            return copy?.toLocalRecord(familyId = record.familyId ?: familyId) ?: record.copy(dirty = false)
        }

        val resolved = local.map(::resolve)
        val resolvedKeys = resolved.map { it.key }.toSet()
        val appended = pushed.filter { it.key !in resolvedKeys }.map(::resolve)
        return Settled(resolved + appended, settled)
    }

    /**
     * Anything this run pushed that the server did not acknowledge is a refusal. The server reports each
     * one in `conflicts`, but deriving it here as well means a refusal can never be dropped on the floor
     * because one side forgot to report it.
     */
    private fun unreportedRefusals(
        pushed: List<LocalRecord>,
        accepted: List<RecordKey>,
        reported: List<ConflictNotice>,
    ): List<ConflictNotice> {
        val acceptedKeys = accepted.toSet()
        val reportedKeys = reported.map { it.key }.toSet()
        return pushed
            .filter { it.key !in acceptedKeys && it.key !in reportedKeys }
            .map { ConflictNotice(table = it.table, id = it.id, wonByMemberId = null) }
    }

    /**
     * A server refusal wins over a locally detected conflict for the same record: the server is the one that
     * compared versions, and only it knows the reason. A notice synthesised locally fills a gap the server
     * left, and a real server reason always beats one.
     */
    private fun mergeNotices(
        server: List<ConflictNotice>,
        local: List<ConflictNotice>,
        unreported: List<ConflictNotice>,
    ): List<ConflictNotice> {
        val merged = LinkedHashMap<RecordKey, ConflictNotice>()
        unreported.forEach { merged[it.key] = it }
        local.forEach { merged.putIfAbsent(it.key, it) }
        server.forEach { merged.putIfAbsent(it.key, it) }
        return merged.values.toList()
    }
}