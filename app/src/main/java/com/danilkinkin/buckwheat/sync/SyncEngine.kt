package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException

/** The server caps a pull window, so a first sync can need more than one round trip. */
private const val MAX_SYNC_PAGES = 200

/**
 * Records nothing. Used only as the default for [SyncEngine.syncStateStore], so a caller that is not
 * the production graph does not silently start claiming that its runs are observed.
 */
internal object NoopSyncStateStore : SyncStateStore {
    override fun cursor(): Flow<Long> = flowOf(0L)
    override suspend fun readCursor(): Long = 0L
    override suspend fun writeCursor(cursor: Long) = Unit
    override fun conflicts(): Flow<List<ConflictNotice>> = flowOf(emptyList())
    override suspend fun readConflicts(): List<ConflictNotice> = emptyList()
    override suspend fun replaceConflicts(conflicts: List<ConflictNotice>) = Unit
    override fun lastSyncedAt(): Flow<Long> = flowOf(0L)
    override fun lastError(): Flow<String?> = flowOf(null)
    override suspend fun markSynced(at: Long) = Unit
    override suspend fun markFailed(reason: String?) = Unit
    override suspend fun clear() = Unit
}

/**
 * [syncStateStore] and [clock] exist only to make a run observable: without them a run that fails all
 * five WorkManager retries is indistinguishable from one that worked. They are defaulted rather than
 * required so that the direct constructions in the engine and worker tests keep compiling; the Hilt
 * graph always supplies the real ones.
 */
class SyncEngine(
    private val client: SyncClient,
    private val database: SyncDatabase,
    private val sessionProvider: suspend () -> FamilySession?,
    private val syncStateStore: SyncStateStore = NoopSyncStateStore,
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    /**
     * Every outcome a run can report leaves a trace in the store, so a failure that outlives its last
     * retry is still visible afterwards instead of being computed and thrown away.
     *
     * The write is best effort: it is a DataStore write on a path that is already reporting trouble,
     * and it must never change the [SyncOutcome] the worker turns into a retry or a failure.
     */
    private suspend fun failed(reason: String): SyncOutcome.Failed {
        runCatching { syncStateStore.markFailed(reason) }
        return SyncOutcome.Failed(reason)
    }

    suspend fun sync(): SyncOutcome = mutex.withLock { runSync() }

    private suspend fun runSync(): SyncOutcome {
        // Nothing is recorded here on purpose. A device that is not enrolled has no family to be out
        // of date with, so neither stamping a time nor raising an error would be a true statement.
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
                return failed(e.message ?: "push failed")
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

            // Order is load-bearing: the tick is only written once the Room transaction above has
            // returned. A process that dies inside that transaction never reaches this line, so a
            // crash can never leave behind a green "synced" claiming the family already has rows that
            // were never applied.
            runCatching { syncStateStore.markSynced(clock.now()) }

            SyncOutcome.Synced(cursor = merged.cursor, conflicts = notices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncPayloadException) {
            // A payload the server sent cannot be turned into a row. Naming it is the difference between
            // "a member's income turned into a spend" and an anonymous failure.
            failed("bad payload: ${e.message}")
        } catch (e: JSONException) {
            failed("bad payload: ${e.message}")
        } catch (e: Exception) {
            failed(e.message ?: "apply failed")
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