package family.sync.sync

import family.sync.db.setUuid
import family.sync.family.BadRequestException
import family.sync.family.ConflictException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class SqlType(val cast: String) {
    TEXT("text"),
    NUMERIC("numeric"),
    BIGINT("bigint"),
    INTEGER("integer"),
    BOOLEAN("boolean"),

    /**
     * `archived_transactions.period_id` is a uuid foreign key, so the placeholder has to say so.
     * Declaring it TEXT would emit `?::text::text` and Postgres would reject the write with a 500.
     */
    UUID("uuid"),
}

/**
 * Keeps the INSERT arm of a tombstone's upsert satisfiable if it is ever reached.
 *
 * [SyncStore.write] skips a tombstone whose row is absent, so this is normally dead: a tombstone that
 * gets written always takes the ON CONFLICT UPDATE arm, which never reads these. It stays because the
 * placeholders exist for every column regardless, and binding NULL into a NOT NULL column would turn a
 * logic slip into an opaque driver error rather than a constraint violation naming the column.
 */
fun zeroValue(column: PayloadColumn): String = when (column.type) {
    SqlType.TEXT -> ""
    SqlType.NUMERIC -> "0"
    SqlType.BIGINT -> "0"
    SqlType.INTEGER -> "0"
    SqlType.BOOLEAN -> "false"

    // Only reachable in the race the tombstone existence probe cannot cover. The all-zero uuid keeps
    // the write syntactically valid; it will still fail the foreign key, which is the honest outcome
    // for a period id nobody supplied.
    SqlType.UUID -> "00000000-0000-0000-0000-000000000000"
}

data class PayloadColumn(
    val key: String,
    val column: String,
    val type: SqlType,
    val nullable: Boolean,
    /** Closed set of legal values; null when the column is free-form. */
    val allowedValues: Set<String>? = null,
)

data class TableSpec(
    val name: String,
    val hasMember: Boolean,
    val columns: List<PayloadColumn>,
)

object SyncTables {

    val ALL: List<TableSpec> = listOf(
        TableSpec(
            name = "transactions",
            hasMember = true,
            columns = listOf(
                PayloadColumn("type", "type", SqlType.TEXT, false, TRANSACTION_TYPES),
                PayloadColumn("value", "value", SqlType.NUMERIC, false),
                PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
                // Optional, and deliberately so: `comment` is the one field a client may have nothing
                // to say about. Declaring it required made a commentless spend a bind failure rather
                // than a stored absence. The column was relaxed to match in V6.
                PayloadColumn("comment", "comment", SqlType.TEXT, true),
                PayloadColumn("category", "category", SqlType.TEXT, true),
            ),
        ),
    )

    fun require(name: String): TableSpec =
        ALL.firstOrNull { it.name == name } ?: throw BadRequestException("unknown_table")
}

data class WireRecord(
    val table: String,
    val id: String,
    val seq: Long,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val memberId: String?,
)

data class AcceptedWrite(val table: String, val id: String)

data class RejectedWrite(
    val table: String,
    val id: String,
    val reason: RejectReason,
    val wonByMemberId: String?,
)

/**
 * Upper bound on changes accepted in one request. Every change is one upsert, so this caps the write
 * amplification and how long a sync holds locks on the family's tables. Clients send a screenful at a
 * time; this is a guard rail against a runaway queue, not the normal batch size.
 */
const val MAX_CHANGES = 1000

/**
 * Upper bound on rows returned by one pull. A first sync at cursor 0 would otherwise pull a family's
 * entire history in a single response. The client repeats the request with the returned cursor while
 * [SyncOutcome.hasMore] is true; absent or false means there is nothing left.
 */
const val MAX_PULL_ROWS = 500

data class SyncOutcome(
    val cursor: Long,
    val accepted: List<AcceptedWrite>,
    val records: List<WireRecord>,
    val rejected: List<RejectedWrite>,
    val hasMore: Boolean,
)

private const val LOCK_TIMEOUT_SQL_STATE = "55P03"

/**
 * A database error that is genuinely the client's fault, naming the change that caused it. Kept
 * distinct from [family.sync.family.ApiException] so it stays a hard failure rather than becoming a
 * per-record rejection the batch can shrug off.
 */
class SyncWriteException(val table: String, val id: String, cause: Throwable) :
    RuntimeException("sync write failed for $table/$id: ${cause.message}", cause)

class SyncStore(private val dataSource: DataSource) {

    private val uuidPattern =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun requireUuid(value: String) {
        if (!uuidPattern.matches(value)) {
            throw BadRequestException("id_not_a_uuid")
        }
    }

    fun sync(
        familyId: String,
        memberId: String,
        cursor: Long,
        changes: List<PushChange>,
        since: Long? = null,
    ): SyncOutcome {
        requireUuid(familyId)
        requireUuid(memberId)
        if (changes.size > MAX_CHANGES) throw BadRequestException("too_many_changes")
        changes.forEach { requireUuid(it.id) }

        // Resolve every change against its table and validate every payload before the first write, so
        // a malformed change fails the whole request instead of aborting the batch halfway and leaving
        // the client's queue stuck behind a row that was never accepted.
        val prepared = changes.map { change ->
            val spec = SyncTables.require(change.table)
            // A tombstone arrives as an empty payload object: the client has already dropped the row
            // locally, so its business columns are gone and irrelevant.
            val values = if (change.deletedAt == null) readPayload(change.payload, spec) else null
            PreparedChange(change, spec, values)
        }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("set lock_timeout = '4s'").use { it.execute() }
            try {
                val accepted = mutableListOf<AcceptedWrite>()
                val rejected = mutableListOf<RejectedWrite>()

                for (entry in prepared) {
                    val change = entry.change
                    val stored = loadStored(connection, entry.spec, change.id, familyId)
                    when (
                        val authorization = authorize(
                            connection = connection,
                            familyId = familyId,
                            entry = entry,
                            memberId = memberId,
                        )
                    ) {
                        is Authorization.Denied -> {
                            rejected.add(
                                RejectedWrite(
                                    change.table,
                                    change.id,
                                    authorization.reason,
                                    authorization.wonByMemberId,
                                )
                            )
                            continue
                        }

                        Authorization.Allowed -> Unit
                    }
                    when (val decision = decidePush(stored, change)) {
                        is MergeDecision.Accept -> {
                            val written = try {
                                write(connection, entry, familyId, memberId, decision.version)
                            } catch (failure: SQLException) {
                                if (failure.sqlState == LOCK_TIMEOUT_SQL_STATE) {
                                    throw ConflictException("sync_lock_timeout")
                                }
                                throw SyncWriteException(change.table, change.id, failure)
                            }
                            if (written) {
                                accepted.add(AcceptedWrite(change.table, change.id))
                            } else {
                                // The id is already taken by another family's row. That is one unwritable
                                // record, not a broken batch, so the rest of the batch still commits.
                                rejected.add(
                                    RejectedWrite(
                                        table = change.table,
                                        id = change.id,
                                        reason = RejectReason.CROSS_FAMILY_WRITE,
                                        wonByMemberId = null,
                                    )
                                )
                            }
                        }

                        is MergeDecision.Reject -> rejected.add(
                            RejectedWrite(
                                table = change.table,
                                id = change.id,
                                reason = decision.reason,
                                wonByMemberId = decision.winnerMemberId,
                            )
                        )
                    }
                }

                val page = pull(connection, familyId, cursor, since)
                connection.commit()

                return SyncOutcome(
                    cursor = page.records.maxOfOrNull { it.seq } ?: cursor,
                    accepted = accepted,
                    records = page.records,
                    rejected = rejected,
                    hasMore = page.hasMore,
                )
            } catch (failure: Throwable) {
                connection.rollback()
                if (failure is SQLException && failure.sqlState == LOCK_TIMEOUT_SQL_STATE) {
                    throw ConflictException("sync_lock_timeout")
                }
                throw failure
            }
        }
    }

    /** A push whose table and payload are already resolved, ready to be written. */
    private class PreparedChange(
        val change: PushChange,
        val spec: TableSpec,
        /**
         * Column values in [TableSpec.columns] order, or null for a tombstone.
         */
        val values: List<String?>?,
    ) {
        val isTombstone: Boolean get() = values == null
    }

    private fun loadStored(
        connection: Connection,
        spec: TableSpec,
        id: String,
        familyId: String,
    ): StoredRecord? {
        val memberColumn = if (spec.hasMember) ", member_id" else ""
        val sql = "select version, updated_at, deleted_at$memberColumn from ${spec.name} " +
            "where id = ?::uuid and family_id = ?::uuid"
        connection.prepareStatement(sql).use { statement ->
            statement.setUuid(1, id)
            statement.setUuid(2, familyId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                return StoredRecord(
                    id = id,
                    version = rows.getInt(1),
                    updatedAt = rows.getLong(2),
                    deletedAt = nullableLong(rows, 3),
                    memberId = if (spec.hasMember) rows.getString(4) else null,
                )
            }
        }
    }

    /**
     * Returns false when the id belongs to another family, so the family-guarded upsert matched
     * nothing. A tombstone for an absent row is accepted and writes nothing.
     */
    private sealed interface Authorization {
        data object Allowed : Authorization

        /**
         * [wonByMemberId] is whose row it actually is, so the client is told who to reconcile against
         * instead of being told only that it lost. Null when the row's author has left the family,
         * which means there is nobody to reconcile against.
         */
        data class Denied(val reason: RejectReason, val wonByMemberId: String?) : Authorization
    }

    /**
     * Whether the server holds this id in this family, and whose row it is when it does.
     *
     * [Owned] with a null [Owned.memberId] is the state a row is left in when its author leaves:
     * that needs a table whose author column survives the departure as a null. `transactions.member_id`
     * is `on delete set null` (`V1:52`), whereas `member_tokens.member_id` is `not null` and
     * `on delete cascade` (`V1:28`), so those rows are deleted rather than left ownerless. That is
     * deliberately a different answer from [Absent], because it needs a different decision.
     */
    private sealed interface Ownership {
        data object Absent : Ownership

        data class Owned(val memberId: String?) : Ownership
    }

    /**
     * Probes [spec]'s table, so the ownership rule follows the table rather than a name typed here.
     *
     * The table name is interpolated because a hardcoded one would be silently wrong the moment a
     * second membered table appeared: the probe would find no row, answer [Ownership.Absent], and allow
     * the write. A table with no `member_id` has no ownership to enforce, so it answers
     * [Ownership.Absent] without querying -- there is no author to compare and no author to protect.
     */
    private fun storedOwnership(connection: Connection, spec: TableSpec, familyId: String, id: String): Ownership {
        if (!spec.hasMember) return Ownership.Absent

        return connection.prepareStatement(
            "select member_id::text from ${spec.name} where id = ?::uuid and family_id = ?::uuid"
        ).use { statement ->
            statement.setUuid(1, id)
            statement.setUuid(2, familyId)
            statement.executeQuery().use { rows ->
                if (rows.next()) Ownership.Owned(rows.getString(1)) else Ownership.Absent
            }
        }
    }

    /**
     * A row in a membered table belongs to the member who wrote it, and to nobody else.
     *
     * Two halves, and both matter. The payload cannot choose the author, because `member_id` is not a
     * wire field at all -- [write] stamps it from the verified token, and the conflict arm no longer
     * rebinds it. And the stored author cannot be overwritten, because a row that another member owns
     * is refused before the merge is even decided. Either half alone is enough to be wrong: trusting the
     * payload lets a client attribute a spend to somebody else, and trusting the upsert lets one member
     * take a row over by editing it once.
     *
     * Judged from the stored row rather than the incoming payload, which for a tombstone is empty --
     * deciding deletes from the payload would let a member step around the rule by deleting the row
     * instead of writing it.
     *
     * A null author is a refusal, not a permission. [Ownership.Owned] with a null member is what a row
     * looks like after its author leaves, and reading that as "nobody owns it, so it is free" hands the
     * departed member's ledger to whoever is left: any surviving member could restate the amount or
     * tombstone the row away. There is no successor to inherit a spend and no one to reconcile
     * against, so the row stays frozen and the client is told the reason instead. Only
     * [Ownership.Absent] is writable -- an insert the writer owns from the start, or a tombstone for a
     * row the writer's own family never had, which is the accepted no-op [write] already handles -- and
     * a table with no member column is allowed for the same reason, having nothing to attribute.
     */
    private fun authorize(
        connection: Connection,
        familyId: String,
        entry: PreparedChange,
        memberId: String,
    ): Authorization =
        when (val ownership = storedOwnership(connection, entry.spec, familyId, entry.change.id)) {
            Ownership.Absent -> Authorization.Allowed
            is Ownership.Owned ->
                if (ownership.memberId == memberId) {
                    Authorization.Allowed
                } else {
                    Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE, ownership.memberId)
                }
        }

    private fun write(
        connection: Connection,
        entry: PreparedChange,
        familyId: String,
        memberId: String,
        version: Int,
    ): Boolean {
        val change = entry.change
        val spec = entry.spec
        if (entry.isTombstone && !rowExists(connection, spec, change.id)) {
            // A tombstone exists to stop a row coming back on a pull. With no row there is nothing to
            // retract and nothing to remember, and the client has already dropped its own copy so a
            // pull cannot resurrect it. Materialising one would mean inventing business columns the
            // client no longer holds, which for archived_transactions means a period_id that has to
            // reference a budget period this server may never have seen.
            return true
        }

        val targetColumns = buildList {
            add("id")
            add("family_id")
            if (spec.hasMember) add("member_id")
            spec.columns.forEach { add(it.column) }
            add("seq")
            add("updated_at")
            add("version")
            add("deleted_at")
        }
        val placeholders = buildList {
            add("?::uuid")
            add("?::uuid")
            if (spec.hasMember) add("?::uuid")
            spec.columns.forEach { add("?::text::${it.type.cast}") }
            add("nextval('sync_sequence')")
            add("?::bigint")
            add("?::integer")
            add("?::bigint")
        }
        val updates = buildList {
            // `member_id` is stamped from the verified token on insert and is deliberately absent here:
            // rebinding it on update would let any member take a row over by editing it once, which is
            // exactly what `authorize` refuses. `family_state`/`period_limits`/`spend_assignments` had no
            // such column to protect, which is part of why the contract is now a single table.
            //
            // A tombstone carries no payload, so its business columns keep whatever the row already
            // holds instead of being flattened to zero values on the way out.
            if (!entry.isTombstone) spec.columns.forEach { add("${it.column} = excluded.${it.column}") }
            add("seq = nextval('sync_sequence')")
            add("updated_at = excluded.updated_at")
            add("version = excluded.version")
            add("deleted_at = excluded.deleted_at")
        }
        val sql = "insert into ${spec.name} (${targetColumns.joinToString(", ")}) " +
            "values (${placeholders.joinToString(", ")}) " +
            "on conflict (id) do update set ${updates.joinToString(", ")} " +
            "where ${spec.name}.family_id = excluded.family_id"

        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setUuid(index++, change.id)
            statement.setUuid(index++, familyId)
            if (spec.hasMember) statement.setUuid(index++, memberId)
            spec.columns.forEachIndexed { offset, column ->
                val value = entry.values?.get(offset)
                    ?: if (column.nullable) null else zeroValue(column)
                if (value == null) {
                    statement.setNull(index++, Types.VARCHAR)
                } else {
                    statement.setString(index++, value)
                }
            }
            statement.setLong(index++, change.updatedAt)
            statement.setInt(index++, version)
            if (change.deletedAt == null) {
                statement.setNull(index, Types.BIGINT)
            } else {
                statement.setLong(index, change.deletedAt)
            }
            return statement.executeUpdate() > 0
        }
    }

    /**
     * Whether any family holds this id. Deliberately unscoped: an id owned by another family still
     * exists, so the tombstone goes on to the guarded upsert and surfaces as a cross_family_write
     * conflict rather than being quietly swallowed as an accepted no-op.
     */
    private fun rowExists(connection: Connection, spec: TableSpec, id: String): Boolean =
        connection.prepareStatement("select 1 from ${spec.name} where id = ?::uuid").use { statement ->
            statement.setUuid(1, id)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun pull(connection: Connection, familyId: String, cursor: Long, since: Long?): PullPage {
        val window = readWindow(connection, familyId, cursor, since)
        val page = window.take(MAX_PULL_ROWS)
        val records = page.groupBy { it.table }
            .flatMap { (table, keys) -> readRows(connection, SyncTables.require(table), familyId, keys.map { it.id }) }
            .sortedBy { it.seq }
        return PullPage(records, hasMore = window.size > MAX_PULL_ROWS)
    }

    /**
     * The next rows across every synced table ordered by the shared sequence, plus one look-ahead row
     * so [PullPage.hasMore] is known without a second count query.
     *
     * [since] bounds the pull by wall clock as well as by cursor. A client that already holds a full
     * ledger can hand back the timestamp it last saw and receive only what moved after it, which is
     * the cheap way to check for another member's activity without paging the whole family history.
     *
     * The two bounds are not independent, and the interaction is destructive. `since` filters the rows
     * the cursor would otherwise have returned, and the cursor the client stores next is the highest
     * `seq` of *this page* -- so any record excluded by `since` is skipped permanently once the client
     * advances past its `seq`. That is the intended reading for the caller who uses `since`, which is
     * the client asking "what moved after T" and treating T as its new floor: everything before T is
     * already held, and everything after T arrives in order. It is a trap for a caller that expects
     * `since` to be a narrowing of the same stream: a record written at T-1 but touched after T
     * arrives, one untouched since before T does not, and neither can be recovered by paging from the
     * returned cursor. Do not hand `since` to a client that still wants to catch up on what it missed.
     */
    private fun readWindow(
        connection: Connection,
        familyId: String,
        cursor: Long,
        since: Long?,
    ): List<WindowRow> {
        val members = SyncTables.ALL.joinToString("\n union all\n") { spec ->
            "select '${spec.name}' as table_name, id, seq from ${spec.name} " +
                "where family_id = ?::uuid and seq > ? and (?::bigint is null or updated_at >= ?::bigint)"
        }
        val sql = "select table_name, id, seq from ($members) synced order by seq, table_name limit ?"
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            SyncTables.ALL.forEach {
                statement.setUuid(index++, familyId)
                statement.setLong(index++, cursor)
                // The predicate tests the bound itself rather than testing for null in Kotlin, so the
                // statement keeps one shape and one plan cache entry. The unused placeholder still has
                // to be bound, and binding a sentinel would quietly start filtering on it, so it gets
                // an explicit null.
                if (since == null) {
                    statement.setNull(index, Types.BIGINT)
                    statement.setNull(index + 1, Types.BIGINT)
                } else {
                    statement.setLong(index, since)
                    statement.setLong(index + 1, since)
                }
                index += 2
            }
            statement.setInt(index, MAX_PULL_ROWS + 1)
            statement.executeQuery().use { rows ->
                val window = mutableListOf<WindowRow>()
                while (rows.next()) {
                    window.add(WindowRow(rows.getString(1), rows.getString(2), rows.getLong(3)))
                }
                return window
            }
        }
    }

    private fun readRows(
        connection: Connection,
        spec: TableSpec,
        familyId: String,
        ids: List<String>,
    ): List<WireRecord> {
        if (ids.isEmpty()) return emptyList()
        val idPlaceholders = ids.joinToString(", ") { "?::uuid" }
        val sql = "select ${selectColumns(spec)} from ${spec.name} " +
            "where family_id = ?::uuid and id in ($idPlaceholders)"
        return connection.prepareStatement(sql).use { statement ->
            statement.setUuid(1, familyId)
            ids.forEachIndexed { offset, id -> statement.setUuid(offset + 2, id) }
            statement.executeQuery().use { rows ->
                val records = mutableListOf<WireRecord>()
                while (rows.next()) {
                    var index = 1
                    val id = rows.getString(index++)
                    val seq = rows.getLong(index++)
                    val updatedAt = rows.getLong(index++)
                    val version = rows.getInt(index++)
                    val deletedAt = nullableLong(rows, index++)
                    val memberId = if (spec.hasMember) rows.getString(index++) else null
                    val payload = buildJsonObject {
                        spec.columns.forEach { column ->
                            put(column.key, readColumn(rows, index++, column))
                        }
                    }
                    records.add(
                        WireRecord(
                            table = spec.name,
                            id = id,
                            seq = seq,
                            updatedAt = updatedAt,
                            version = version,
                            deletedAt = deletedAt,
                            payload = payload.toString(),
                            memberId = memberId,
                        )
                    )
                }
                return records
            }
        }
    }

    private fun readColumn(rows: ResultSet, index: Int, column: PayloadColumn): JsonElement {
        if (rows.getObject(index) == null) return JsonNull
        return when (column.type) {
            SqlType.BOOLEAN -> JsonPrimitive(rows.getBoolean(index))
            else -> JsonPrimitive(rows.getString(index))
        }
    }

    private fun selectColumns(spec: TableSpec): String = buildList {
        add("id")
        add("seq")
        add("updated_at")
        add("version")
        add("deleted_at")
        if (spec.hasMember) add("member_id")
        spec.columns.forEach { add(it.column) }
    }.joinToString(", ")

    private class PullPage(val records: List<WireRecord>, val hasMore: Boolean)

    private class WindowRow(val table: String, val id: String, val seq: Long)
}

private fun nullableLong(rows: ResultSet, index: Int): Long? {
    val value = rows.getLong(index)
    return if (rows.wasNull()) null else value
}