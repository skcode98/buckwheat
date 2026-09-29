package family.sync.sync

import family.sync.db.setUuid
import family.sync.family.BadRequestException
import family.sync.family.ConflictException
import family.sync.family.ForbiddenException
import java.sql.SQLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.sql.DataSource

enum class SqlType(val cast: String) {
    TEXT("text"),
    NUMERIC("numeric"),
    BIGINT("bigint"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
}

data class PayloadColumn(
    val key: String,
    val column: String,
    val type: SqlType,
    val nullable: Boolean,
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
                PayloadColumn("type", "type", SqlType.TEXT, false),
                PayloadColumn("value", "value", SqlType.NUMERIC, false),
                PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("category", "category", SqlType.TEXT, true),
            ),
        ),
        TableSpec(
            name = "archived_transactions",
            hasMember = true,
            columns = listOf(
                PayloadColumn("type", "type", SqlType.TEXT, false),
                PayloadColumn("value", "value", SqlType.NUMERIC, false),
                PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
                PayloadColumn("comment", "comment", SqlType.TEXT, false),
                PayloadColumn("category", "category", SqlType.TEXT, true),
                PayloadColumn("periodId", "period_id", SqlType.TEXT, false),
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

data class RejectedWrite(val table: String, val id: String, val wonByMemberId: String?)

const val MAX_CHANGES = 1000

data class SyncOutcome(
    val cursor: Long,
    val accepted: List<String>,
    val records: List<WireRecord>,
    val rejected: List<RejectedWrite>,
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
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("set lock_timeout = '4s'").use { it.execute() }
            try {
                val accepted = mutableListOf<String>()
                val rejected = mutableListOf<RejectedWrite>()

                for (change in changes) {
                    val spec = SyncTables.require(change.table)
                    val stored = loadStored(connection, spec, change.id, familyId)
                    when (val decision = decidePush(stored, change)) {
                        is MergeDecision.Accept -> {
                            store(connection, spec, familyId, memberId, change, decision.version)
                            accepted.add(change.id)
                        }

                        is MergeDecision.Reject ->
                            rejected.add(RejectedWrite(change.table, change.id, decision.winnerMemberId))
                    }
                }

                val records = pull(connection, familyId, cursor)
                connection.commit()

                val highestSent = records.maxOfOrNull { it.seq }
                return SyncOutcome(
                    cursor = highestSent ?: cursor,
                    accepted = accepted,
                    records = records,
                    rejected = rejected,
                )
            } catch (failure: Throwable) {
                connection.rollback()
                if (failure is SQLException && failure.sqlState == "55P03") {
                    throw ConflictException("sync_lock_timeout")
                }
                throw failure
            }
        }
    }

    private fun loadStored(
        connection: java.sql.Connection,
        spec: TableSpec,
        id: String,
        familyId: String,
    ): StoredRecord? {
        val memberColumn = if (spec.hasMember) "member_id," else ""
        val sql =
            "select version, updated_at, deleted_at, ${memberColumn}id from ${spec.name} " +
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

    private fun store(
        connection: java.sql.Connection,
        spec: TableSpec,
        familyId: String,
        memberId: String,
        change: PushChange,
        version: Int,
    ) {
        val payload = readPayload(change.payload, spec)
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
            spec.columns.forEach { add("${it.column} = excluded.${it.column}") }
            add("seq = nextval('sync_sequence')")
            add("updated_at = excluded.updated_at")
            add("version = excluded.version")
            add("deleted_at = excluded.deleted_at")
        }
        val sql =
            "insert into ${spec.name} (${targetColumns.joinToString(", ")}) " +
                "values (${placeholders.joinToString(", ")}) " +
                "on conflict (id) do update set ${updates.joinToString(", ")} " +
                "where ${spec.name}.family_id = excluded.family_id"

        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setUuid(index++, change.id)
            statement.setUuid(index++, familyId)
            if (spec.hasMember) statement.setUuid(index++, memberId)
            spec.columns.forEachIndexed { offset, column ->
                val value = payload[offset]
                if (value == null) {
                    statement.setNull(index++, java.sql.Types.VARCHAR)
                } else {
                    statement.setString(index++, value)
                }
            }
            statement.setLong(index++, change.updatedAt)
            statement.setInt(index++, version)
            if (change.deletedAt == null) statement.setNull(index, java.sql.Types.BIGINT) else statement.setLong(index, change.deletedAt)
            val written = statement.executeUpdate()
            if (written == 0) throw ForbiddenException("cross_family_write")
        }
    }

    private fun pull(
        connection: java.sql.Connection,
        familyId: String,
        cursor: Long,
    ): List<WireRecord> {
        val records = mutableListOf<WireRecord>()
        for (spec in SyncTables.ALL) {
            val memberColumn = if (spec.hasMember) "member_id," else ""
            val selectColumns = buildList {
                add("id")
                add("seq")
                add("updated_at")
                add("version")
                add("deleted_at")
                if (spec.hasMember) add("member_id")
                spec.columns.forEach { add(it.column) }
            }
            val sql =
                "select ${selectColumns.joinToString(", ")} from ${spec.name} " +
                    "where family_id = ?::uuid and seq > ? order by seq"
            connection.prepareStatement(sql).use { statement ->
                statement.setUuid(1, familyId)
                statement.setLong(2, cursor)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        var index = 1
                        val id = rows.getString(index++)
                        val seq = rows.getLong(index++)
                        val updatedAt = rows.getLong(index++)
                        val version = rows.getInt(index++)
                        val deletedAt = nullableLong(rows, index++)
                        val memberId = if (spec.hasMember) rows.getString(index++) else null
                        val payload = JsonObject(
                            spec.columns.associate { column ->
                                column.key to if (column.type == SqlType.BOOLEAN) {
                                    JsonPrimitive(rows.getBoolean(index++))
                                } else {
                                    JsonPrimitive(rows.getString(index++))
                                }
                            }
                        )
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
                }
            }
        }
        return records.sortedBy { it.seq }
    }

    private fun readPayload(raw: String, spec: TableSpec): List<String?> {
        val parsed = Json.parseToJsonElement(raw) as? JsonObject
            ?: throw BadRequestException("payload_invalid")
        return spec.columns.map { column ->
            val element = parsed[column.key]
            if (element == null || element is JsonNull) {
                if (column.nullable) return@map null
                throw BadRequestException("payload_incomplete")
            }
            val primitive = element as? JsonPrimitive ?: throw BadRequestException("payload_invalid")
            requireValidValue(primitive, column.type)
            primitive.content
        }
    }

    private fun requireValidValue(primitive: JsonPrimitive, type: SqlType) {
        val text = primitive.content
        val valid = when (type) {
            SqlType.TEXT -> true
            SqlType.BOOLEAN -> if (primitive.isString) {
                text.equals("true", ignoreCase = true) || text.equals("false", ignoreCase = true)
            } else {
                text == "true" || text == "false"
            }

            SqlType.INTEGER, SqlType.BIGINT -> text.toLongOrNull() != null
            SqlType.NUMERIC -> text.toBigDecimalOrNull() != null
        }
        if (!valid) throw BadRequestException("payload_invalid")
    }
}

private fun nullableLong(rows: java.sql.ResultSet, index: Int): Long? {
    val value = rows.getLong(index)
    return if (rows.wasNull()) null else value
}
