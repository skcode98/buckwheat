package family.sync

import family.sync.db.setUuid
import family.sync.sync.SyncTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaMigrationTest {

    @Test
    fun migrationCreatesEverySyncedTable() {
        val tables = tableNames()
        listOf(
            "families", "members", "invites", "member_tokens",
            "transactions", "archived_transactions", "budget_periods",
            "family_state", "period_limits",
            "saved_categories", "saved_tags", "recurring_templates", "savings_goals",
            "family_settings",
        ).forEach { assertTrue(it in tables, "missing table $it") }
    }

    @Test
    fun theSharedSequenceExists() {
        assertTrue(sequenceNames().contains("sync_sequence"), "missing sequence sync_sequence")
    }

    @Test
    fun everySyncedTableCarriesTheSyncColumns() {
        val expected = setOf("family_id", "seq", "updated_at", "version", "deleted_at")
        listOf("transactions", "saved_tags", "savings_goals", "budget_periods").forEach { table ->
            val columns = columnNames(table)
            assertTrue(
                expected.all { it in columns },
                "$table missing ${expected - columns}",
            )
        }
    }

    @Test
    fun transactionsAndArchivedRowsCarryAMemberId() {
        listOf("transactions", "archived_transactions").forEach { table ->
            assertTrue("member_id" in columnNames(table), "$table missing member_id")
        }
    }

    @Test
    fun aSingleSequenceBacksEverySyncedTable() {
        val defaults = listOf("transactions", "archived_transactions", "saved_categories")
            .map { columnDefault(it, "seq") }
            .toSet()
        assertEquals(1, defaults.size, "seq defaults differ per table: $defaults")
        assertTrue(
            defaults.first().contains("sync_sequence"),
            "unexpected default ${defaults.first()}",
        )
    }

    @Test
    fun theSequenceIsStrictlyIncreasing() {
        TestDatabase.dataSource.connection.use { connection ->
            val familyId = TestDatabase.insertUuid(
                connection,
                "insert into families default values returning id",
            )
            val first = nextSeq(connection, familyId)
            val second = nextSeq(connection, familyId)
            assertTrue(second > first, "sequence did not advance: $first then $second")
        }
    }

    @Test
    fun familyStateIsOneRowPerFamily() {
        assertEquals(
            setOf("family_id"),
            TestDatabase.primaryKeys("family_state"),
            "family_state primary key should be family_id",
        )
    }

    @Test
    fun savingsGoalsCarryTheirName() {
        assertTrue(
            "name" in columnNames("savings_goals"),
            "savings_goals missing name",
        )
    }

    @Test
    fun everyPayloadColumnExistsInItsTable() {
        val mismatches = mutableListOf<String>()
        SyncTables.ALL.forEach { spec ->
            val columns = columnNames(spec.name)
            spec.columns.forEach { column ->
                if (column.column !in columns) {
                    mismatches.add("${spec.name}.${column.column}")
                }
            }
        }
        assertEquals(emptyList(), mismatches, "columns missing from the schema")
    }

    @Test
    fun everyFamilyTableHasRowLevelSecurityEnabled() {
        val tables = listOf(
            "families", "members", "invites", "member_tokens",
            "transactions", "archived_transactions", "budget_periods",
            "family_state", "period_limits",
            "saved_categories", "saved_tags", "recurring_templates", "savings_goals",
            "family_settings",
        )
        val unprotected = mutableListOf<String>()
        tables.forEach { table ->
            if (table !in rowLevelSecurityEnabled()) unprotected.add(table)
        }
        assertEquals(emptyList(), unprotected, "tables reachable through the public api")
    }

    private fun rowLevelSecurityEnabled(): Set<String> {
        val names = mutableSetOf<String>()
        TestDatabase.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select relname from pg_class " +
                        "where relnamespace = 'public'::regnamespace and relkind = 'r' and relrowsecurity"
                ).use { rs ->
                    while (rs.next()) names.add(rs.getString(1).lowercase())
                }
            }
        }
        return names
    }

    private fun nextSeq(connection: java.sql.Connection, familyId: String): Long =
        connection.prepareStatement(
            "insert into saved_tags (family_id, name) values (?, ?) returning seq"
        ).use { statement ->
            statement.setUuid(1, familyId)
            statement.setString(2, "tag-${System.nanoTime()}")
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun columnDefault(table: String, column: String): String =
        TestDatabase.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select column_default from information_schema.columns " +
                    "where table_name = ? and column_name = ?"
            ).use { statement ->
                statement.setString(1, table)
                statement.setString(2, column)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1).orEmpty() else ""
                }
            }
        }

    private fun tableNames(): Set<String> {
        val names = mutableSetOf<String>()
        TestDatabase.dataSource.connection.use { connection ->
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) names.add(rs.getString("TABLE_NAME").lowercase())
            }
        }
        return names
    }

    private fun sequenceNames(): Set<String> {
        val names = mutableSetOf<String>()
        TestDatabase.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select sequence_name from information_schema.sequences where sequence_schema = 'public'"
                ).use { rs ->
                    while (rs.next()) names.add(rs.getString(1).lowercase())
                }
            }
        }
        return names
    }

    private fun columnNames(table: String): Set<String> {
        val names = mutableSetOf<String>()
        TestDatabase.dataSource.connection.use { connection ->
            connection.metaData.getColumns(null, "public", table, "%").use { rs ->
                while (rs.next()) names.add(rs.getString("COLUMN_NAME").lowercase())
            }
        }
        return names
    }
}
