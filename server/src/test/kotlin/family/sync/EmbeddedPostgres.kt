package family.sync

import family.sync.db.setUuid
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.TreeMap
import javax.sql.DataSource

data class TestFamily(val familyId: String, val memberId: String)

class EmbeddedPostgresInstance(
    val dataSource: DataSource,
    private val postgres: EmbeddedPostgres,
) : AutoCloseable {
    override fun close() = postgres.close()
}

private const val PG_USER = "postgres"
private const val PG_DATABASE = "postgres"

fun startEmbeddedPostgres(): EmbeddedPostgresInstance {
    val postgres = EmbeddedPostgres.builder()
        .setServerConfig("max_connections", "30")
        .setServerConfig("fsync", "off")
        .start()
    val dataSource = createDataSource(postgres.getJdbcUrl(PG_USER, PG_DATABASE), PG_USER, "")
    return EmbeddedPostgresInstance(dataSource, postgres)
}

object TestDatabase {
    private val instance: EmbeddedPostgresInstance by lazy {
        val started = startEmbeddedPostgres()
        Runtime.getRuntime().addShutdownHook(Thread { started.close() })
        migrate(started.dataSource)
        started
    }

    val dataSource: DataSource get() = instance.dataSource

    fun truncateAll() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "truncate invites, member_tokens, " +
                        "archived_transactions, transactions, budget_periods, saved_categories, " +
                        "saved_tags, recurring_templates, savings_goals, family_settings, " +
                        "members, families restart identity cascade"
                )
            }
        }
    }

    fun isOwner(memberId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("select is_owner from members where id = ?").use { statement ->
            statement.setUuid(1, memberId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean("is_owner") }
        }
    }

    fun isDeparted(memberId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("select departed_at is not null from members where id = ?").use { statement ->
            statement.setUuid(1, memberId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }

    fun createFamily(displayName: String, isOwner: Boolean = true): TestFamily =
        dataSource.connection.use { connection ->
            val familyId = insertUuid(connection, "insert into families default values returning id")
            val memberId = insertUuid(
                connection,
                "insert into members (family_id, display_name, is_owner) values (?, ?, ?) returning id",
                familyId,
                displayName,
                isOwner,
            )
            TestFamily(familyId, memberId)
        }

    fun addMember(familyId: String, displayName: String, isOwner: Boolean = false): String =
        dataSource.connection.use { connection ->
            insertUuid(
                connection,
                "insert into members (family_id, display_name, is_owner) values (?, ?, ?) returning id",
                familyId,
                displayName,
                isOwner,
            )
        }

    /**
     * A usable token for a member that was written straight into the database.
     *
     * The sync suite sets up its families without the invite round-trip, so it needs a way to reach a
     * verified session for a member the API never saw created. This mints one rather than storing a
     * raw token hash, which is the whole point of the scheme.
     */
    fun issueToken(familyId: String, memberId: String): String =
        family.sync.auth.TokenService(dataSource).mint(memberId, familyId)

    fun readTokenHashes(): List<String> {
        val hashes = mutableListOf<String>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select token_hash from member_tokens").use { rs ->
                    while (rs.next()) hashes.add(rs.getString(1))
                }
            }
        }
        return hashes
    }

    fun expireInvite(code: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update invites set expires_at = now() - interval '1 minute' where code = ?"
            ).use { statement ->
                statement.setString(1, code)
                statement.executeUpdate()
            }
        }
    }

    fun countTokens(memberId: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from member_tokens where member_id = ?::uuid").use { statement ->
                statement.setUuid(1, memberId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }

    fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    fun primaryKeys(table: String): Set<String> {
        val names = mutableSetOf<String>()
        dataSource.connection.use { connection ->
            connection.metaData.getPrimaryKeys(null, "public", table).use { rs ->
                while (rs.next()) names.add(rs.getString("COLUMN_NAME"))
            }
        }
        return names
    }

    /**
     * Every unique index on [table], each as the set of its columns.
     *
     * `getPrimaryKeys` only reports the declared primary key, so it cannot see a unique constraint
     * that is doing the real work of enforcing a one-row invariant. `getIndexInfo` returns one row per
     * (index, column) pair, so rows are grouped by `INDEX_NAME` and ordered by `ORDINAL_POSITION`;
     * without that grouping every index on the table collapses into one meaningless set.
     */
    fun uniqueConstraints(table: String): List<Set<String>> {
        val byIndex = linkedMapOf<String, TreeMap<Int, String>>()
        dataSource.connection.use { connection ->
            connection.metaData.getIndexInfo(null, "public", table, true, false).use { rs ->
                while (rs.next()) {
                    val column = rs.getString("COLUMN_NAME") ?: continue
                    val name = rs.getString("INDEX_NAME") ?: continue
                    byIndex.getOrPut(name) { TreeMap() }[rs.getInt("ORDINAL_POSITION")] = column
                }
            }
        }
        return byIndex.values.map { it.values.toSet() }
    }

    fun insertUuid(connection: Connection, sql: String, vararg args: Any?): String {
        if (args.isEmpty()) {
            return connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
        return connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value ->
                if (value is String) {
                    statement.setUuid(index + 1, value)
                } else {
                    statement.setObject(index + 1, value)
                }
            }
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }
}
