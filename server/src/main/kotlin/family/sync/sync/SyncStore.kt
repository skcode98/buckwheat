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
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("category", "category", SqlType.TEXT, true),
                // Nullable, and deliberately so: a client older than the household feature has no
                // `bucket` key at all, and a non-nullable column here would make its entire sync fail
                // `payload_incomplete` so its transactions would never leave the device. Absent means
                // MEMBER, which is what those builds meant anyway. Widen to non-nullable only once no
                // client in the field is old enough to omit it.
                PayloadColumn("bucket", "bucket", SqlType.TEXT, true, BUCKETS),
                PayloadColumn("assignmentId", "assignment_id", SqlType.UUID, true),
                PayloadColumn("assignedByMemberId", "assigned_by_member_id", SqlType.UUID, true),
            ),
        ),
        TableSpec(
            name = "archived_transactions",
            hasMember = true,
            columns = listOf(
                PayloadColumn("type", "type", SqlType.TEXT, false, TRANSACTION_TYPES),
                PayloadColumn("value", "value", SqlType.NUMERIC, false),
                PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("category", "category", SqlType.TEXT, true),
                PayloadColumn("periodId", "period_id", SqlType.UUID, false),
                PayloadColumn("bucket", "bucket", SqlType.TEXT, true, BUCKETS),
                PayloadColumn("assignmentId", "assignment_id", SqlType.UUID, true),
                PayloadColumn("assignedByMemberId", "assigned_by_member_id", SqlType.UUID, true),
            ),
        ),
        TableSpec(
            name = "budget_periods",
            hasMember = false,
            columns = listOf(
                PayloadColumn("budget", "budget", SqlType.NUMERIC, false),
                PayloadColumn("startDate", "start_date", SqlType.BIGINT, false),
                PayloadColumn("finishDate", "finish_date", SqlType.BIGINT, false),
                PayloadColumn("actualFinishDate", "actual_finish_date", SqlType.BIGINT, true),
                PayloadColumn("currency", "currency", SqlType.TEXT, false),
                PayloadColumn("totalSpent", "total_spent", SqlType.NUMERIC, false),
                PayloadColumn("isImported", "is_imported", SqlType.BOOLEAN, false),
            ),
        ),
        TableSpec(
            name = "saved_categories",
            hasMember = false,
            columns = listOf(
                PayloadColumn("name", "name", SqlType.TEXT, false),
                PayloadColumn("emoji", "emoji", SqlType.TEXT, false),
            ),
        ),
        TableSpec(
            name = "saved_tags",
            hasMember = false,
            columns = listOf(
                PayloadColumn("name", "name", SqlType.TEXT, false),
            ),
        ),
        TableSpec(
            name = "recurring_templates",
            hasMember = false,
            columns = listOf(
                PayloadColumn("amount", "amount", SqlType.NUMERIC, false),
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("dayOfMonth", "day_of_month", SqlType.INTEGER, false),
                PayloadColumn("enabled", "enabled", SqlType.BOOLEAN, false),
            ),
        ),
        TableSpec(
            name = "savings_goals",
            hasMember = false,
            columns = listOf(
                PayloadColumn("name", "name", SqlType.TEXT, false),
                PayloadColumn("targetAmount", "target", SqlType.NUMERIC, false),
                PayloadColumn("currentAmount", "current", SqlType.NUMERIC, false),
                PayloadColumn("deadline", "deadline", SqlType.BIGINT, true),
                PayloadColumn("createdAt", "created_at", SqlType.BIGINT, false),
                PayloadColumn("completed", "completed", SqlType.BOOLEAN, false),
            ),
        ),
        TableSpec(
            name = "family_state",
            // The generic writer keys every table on `id` and reads rows back with `where id = ?`, so
            // `id` is a real column here and the client uses it as the record id. V1 keyed this table
            // on `family_id` alone, which is why it could never be a TableSpec: there was no `id` for
            // the writer to address and no `seq` for the cursor. See V5.
            hasMember = false,
            columns = listOf(
                PayloadColumn("budget", "budget", SqlType.NUMERIC, false),
                PayloadColumn("householdTier", "household_tier", SqlType.NUMERIC, false),
                PayloadColumn("startDate", "start_date", SqlType.BIGINT, false),
                PayloadColumn("finishDate", "finish_date", SqlType.BIGINT, false),
                PayloadColumn("currency", "currency", SqlType.TEXT, false),
                PayloadColumn("householdDetailVisibleToAll", "household_detail_visible_to_all", SqlType.BOOLEAN, false),
                PayloadColumn("commonSplitRule", "common_split_rule", SqlType.TEXT, false),
                PayloadColumn("tagsVisibleToSelf", "tags_visible_to_self", SqlType.BOOLEAN, false),
                PayloadColumn("familyAiEnabled", "family_ai_enabled", SqlType.BOOLEAN, false),
            ),
        ),
        TableSpec(
            name = "period_limits",
            hasMember = false,
            columns = listOf(
                PayloadColumn("periodId", "period_id", SqlType.UUID, false),
                PayloadColumn("memberId", "member_id", SqlType.UUID, false),
                PayloadColumn("limitValue", "limit_value", SqlType.NUMERIC, false),
            ),
        ),
        TableSpec(
            name = "spend_assignments",
            hasMember = false,
            columns = listOf(
                PayloadColumn("periodId", "period_id", SqlType.UUID, false),
                PayloadColumn("targetMemberId", "target_member_id", SqlType.UUID, false),
                PayloadColumn("createdByMemberId", "created_by_member_id", SqlType.UUID, false),
                PayloadColumn("amount", "amount", SqlType.NUMERIC, false),
                PayloadColumn("category", "category", SqlType.TEXT, true),
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("date", "date", SqlType.BIGINT, false),
                PayloadColumn("status", "status", SqlType.TEXT, false, ASSIGNMENT_STATUSES),
                PayloadColumn("resolvedAt", "resolved_at", SqlType.BIGINT, true),
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

/**
 * The tables whose contents are governed by *who wrote them*, as opposed to being governed only by
 * family scoping in the SQL.
 *
 * Top level rather than a member of [SyncStore] so a test can read it without an instance, and listed
 * explicitly rather than implied by the `when` in [SyncStore.authorize] -- because default-allow is
 * what lets an ordinary member save a tag or close a period, and a default cannot catch a table that
 * was added to the contract and then forgotten here.
 */
internal val FAMILY_GOVERNED_TABLES = setOf(
    "family_state",
    "period_limits",
    "spend_assignments",
    "transactions",
    "archived_transactions",
)

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
                            stored = stored,
                            memberId = memberId,
                        )
                    ) {
                        is Authorization.Denied -> {
                            rejected.add(
                                RejectedWrite(change.table, change.id, authorization.reason, null)
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

                val page = pull(connection, familyId, cursor)
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
        data class Denied(val reason: RejectReason) : Authorization
    }

    /**
     * Who may write what, enforced here rather than in the app.
     *
     * Every one of these rules exists because the client cannot be the only place they live: the
     * client is whatever version somebody installed, and a stale or modified one would happily write
     * a household expense or resolve a request on somebody else's behalf. The app already refuses all
     * of these; this is the same rule stated once, where it cannot be skipped.
     *
     * - `family_state` and `period_limits` are the head's to set.
     * - A `HOUSEHOLD` transaction spends from the shared tier, so only the head may write one.
     * - An `ARCHIVED_TRANSACTIONS` row records a closed period's history, including its bucket, so the
     *   household rule applies to it too; otherwise a member could archive their way to a rent.
     * - A `spend_assignments` row may be *raised* only by the head. It may be *answered* only by the
     *   member it is aimed at, and only on a row that is actually pending. That last pair is the
     *   consent guarantee: without it a member could accept a request on someone else's behalf, or
     *   resolve their own and skip the question.
     *
     * Deletes are judged from the stored row rather than the incoming payload, which is empty for a
     * tombstone. Deciding deletes from the payload would mean every rule above could be stepped around
     * by deleting the row instead of writing it.
     */
    private fun authorize(
        connection: Connection,
        familyId: String,
        entry: PreparedChange,
        stored: StoredRecord?,
        memberId: String,
    ): Authorization {
        val change = entry.change
        val tombstone = change.deletedAt != null
        val isOwner = isOwnerFor(connection, familyId, memberId)

        // The authoritative copy. Every business column is a real SQL column, so the stored row is read
        // from the table rather than reconstructed from a payload blob. That matters twice over: it is
        // the only place a delete can be judged (an incoming tombstone carries no payload at all), and
        // it cannot disagree with what `write` will actually persist.
        val existing = storedFacts(connection, familyId, change.table, change.id)

        // A tombstone for a row the server has never seen is a no-op that `write` already handles, and the
        // existing contract accepts it. Denying it here broke a long-standing behaviour for no gain: a
        // member deleting their own row that never synced cannot affect anyone else's data. What must
        // not happen is a member deleting a row that *is* stored and is a household row, and that is
        // judged below from the stored bucket.
        if (tombstone && existing == null) {
            return Authorization.Allowed
        }

        when (change.table) {
            "family_state", "period_limits" ->
                return if (isOwner) Authorization.Allowed else Authorization.Denied(RejectReason.OWNER_ONLY)

            "spend_assignments" -> {
                // Facts come from the stored row whenever it exists. An incoming value is never trusted
                // for an existing row, because a client could otherwise re-target somebody else's
                // request at themselves and then answer it.
                val target = existing?.target ?: entry.incoming("targetMemberId")
                val creator = existing?.creator ?: entry.incoming("createdByMemberId")
                val alreadyResolved = existing?.resolved == true

                return when {
                    // Withdrawing: the head raised it, the target owns it, so either may remove it, and
                    // to nobody else.
                    tombstone -> when {
                        isOwner || creator == memberId || target == memberId -> Authorization.Allowed
                        else -> Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)
                    }

                    // Answering. Requires a stored row, so a member cannot invent an already-answered
                    // request aimed at themselves and skip the question entirely. Only the target may
                    // answer, and neither the target nor the creator nor the amount may be restated on
                    // the way through: "the head proposes, the member consents" means the amount
                    // consented to is the amount proposed.
                    existing != null -> when {
                        alreadyResolved ->
                            Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)

                        target != memberId ->
                            Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)

                        entry.incoming("targetMemberId") != null &&
                            entry.incoming("targetMemberId") != existing.target ->
                            Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)

                        entry.incoming("amount") != null &&
                            !sameAmount(entry.incoming("amount"), existing.amount) ->
                            Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)

                        // Who asked may not be restated either. Not privilege escalation -- the target
                        // could simply delete the request -- but a row that later reads as self-raised
                        // is a different row from the one the head wrote, and the audit trail is the
                        // whole point of keeping the assignment after it is answered.
                        entry.incoming("createdByMemberId") != null &&
                            entry.incoming("createdByMemberId") != existing.creator ->
                            Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE)

                        else -> Authorization.Allowed
                    }

                    // Raising. The head only, never aimed at themselves: that would be a member writing
                    // their own spend by another route, bypassing the consent this object exists for.
                    //
                    // And a new request has to be genuinely new. Without the status and resolvedAt
                    // check the head could raise one already marked ACCEPTED, which the target could
                    // then never answer -- the money committed and the consent step skipped. Closing
                    // the set of legal values did not stop this; it only made the payload tidier on its
                    // way to the hole.
                    else -> if (
                        isOwner &&
                        creator == memberId &&
                        target != null &&
                        target != memberId &&
                        (entry.incoming("status") ?: "PENDING") == "PENDING" &&
                        entry.incoming("resolvedAt").isNullOrBlank()
                    ) {
                        Authorization.Allowed
                    } else {
                        Authorization.Denied(RejectReason.OWNER_ONLY)
                    }
                }
            }

            "transactions", "archived_transactions" -> {
                // A household row spends from the shared tier. BOTH sides of the comparison matter:
                // the stored bucket, so a member cannot downgrade the head's rent to a personal spend;
                // and the incoming one, so a member cannot upgrade their own personal spend into the
                // shared tier and have the head's household total absorb it. Testing only the stored
                // value silently permitted the second direction.
                val storedBucket = existing?.bucket
                val incomingBucket = entry.incoming("bucket")
                if ((storedBucket == "HOUSEHOLD" || incomingBucket == "HOUSEHOLD") && !isOwner) {
                    return Authorization.Denied(RejectReason.OWNER_ONLY)
                }
                // No check on member_id, because there is nothing to check: the write path binds it from
                // the authenticated principal, so a client-declared member is already overwritten.
                // Attribution is server-assigned rather than self-reported, and that is what makes a
                // per-member ledger worth anything.
                return Authorization.Allowed
            }

            // Personal tables: budget periods, saved categories and tags, recurring templates, savings
            // goals. Governed by the family scoping in the SQL itself -- every read and write filters on
            // `family_id` -- and by nothing else, so they pass through untouched. Failing closed here
            // would deny everybody their own spending, which is the worst possible failure mode for a
            // security rule. A table that *is* family-governed belongs in FAMILY_GOVERNED_TABLES, and
            // `aGovernedTableHasARule` fails if one is listed without a branch above.
            else -> return Authorization.Allowed
        }
    }

/** The stored row's business columns, as the database holds them. */
    private data class StoredFacts(
        val bucket: String?,
        val target: String?,
        val creator: String?,
        val amount: String?,
        val status: String?,
    ) {
        val resolved: Boolean get() = status != null && status != "PENDING"
    }

    private fun storedFacts(
        connection: Connection,
        familyId: String,
        table: String,
        id: String,
    ): StoredFacts? {
        val columns = when (table) {
            "transactions", "archived_transactions" -> "bucket, null, null, null, null"
            "spend_assignments" -> "null, target_member_id, created_by_member_id, amount, status"
            else -> return null
        }
        return connection.prepareStatement(
            "select $columns from $table where id = ?::uuid and family_id = ?::uuid"
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, familyId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                StoredFacts(
                    bucket = rows.getString(1),
                    target = rows.getString(2),
                    creator = rows.getString(3),
                    amount = rows.getString(4),
                    status = rows.getString(5),
                )
            }
        }
    }

    private fun PreparedChange.incoming(key: String): String? =
        values?.getOrNull(spec.columns.indexOfFirst { it.key == key })?.takeIf { it.isNotBlank() }

    /**
     * Compares two amounts by value, not by text.
     *
     * The stored row comes back from a `numeric` column and the incoming one arrives as whatever the
     * client typed, so `"250"`, `"250.0"` and `"250.00"` are the same money written three ways. Plain
     * string equality would read a target restating the amount they were asked for as an attempt to
     * change it, and refuse the one request they were entitled to make.
     */
    private fun sameAmount(incoming: String?, stored: String?): Boolean {
        if (incoming == null || stored == null) return incoming == stored
        val left = runCatching { java.math.BigDecimal(incoming) }.getOrNull() ?: return incoming == stored
        val right = runCatching { java.math.BigDecimal(stored) }.getOrNull() ?: return incoming == stored
        return left.compareTo(right) == 0
    }

    private fun isOwnerFor(connection: Connection, familyId: String, memberId: String): Boolean =
        connection.prepareStatement(
            "select is_owner from members where id = ?::uuid and family_id = ?::uuid"
        ).use { statement ->
            statement.setString(1, memberId)
            statement.setString(2, familyId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
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
            if (spec.hasMember) add("member_id = excluded.member_id")
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

    private fun pull(connection: Connection, familyId: String, cursor: Long): PullPage {
        val window = readWindow(connection, familyId, cursor)
        val page = window.take(MAX_PULL_ROWS)
        val records = page.groupBy { it.table }
            .flatMap { (table, keys) -> readRows(connection, SyncTables.require(table), familyId, keys.map { it.id }) }
            .sortedBy { it.seq }
        return PullPage(records, hasMore = window.size > MAX_PULL_ROWS)
    }

    /**
     * The next rows across every synced table ordered by the shared sequence, plus one look-ahead row
     * so [PullPage.hasMore] is known without a second count query.
     */
    private fun readWindow(connection: Connection, familyId: String, cursor: Long): List<WindowRow> {
        val members = SyncTables.ALL.joinToString("\n union all\n") { spec ->
            "select '${spec.name}' as table_name, id, seq from ${spec.name} where family_id = ?::uuid and seq > ?"
        }
        val sql = "select table_name, id, seq from ($members) synced order by seq, table_name limit ?"
        return connection.prepareStatement(sql).use { statement ->
            var index = 1
            SyncTables.ALL.forEach {
                statement.setUuid(index++, familyId)
                statement.setLong(index++, cursor)
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