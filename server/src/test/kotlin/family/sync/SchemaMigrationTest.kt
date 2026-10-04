package family.sync

import family.sync.db.setUuid
import family.sync.sync.SyncTables
import org.flywaydb.core.api.FlywayException
import org.junit.Assert.assertThrows
import java.io.File
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaMigrationTest {

    private companion object {
        val CREATE_TABLE =
            Regex("""create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z_][a-z0-9_]*)""", RegexOption.IGNORE_CASE)
    }

    /**
     * The schema inventory, which is deliberately wider than the sync contract.
     *
     * `transactions` is the only table offered to a client now, but the tables the retired contract
     * used are still migrated: dropping them is a separate change, and narrowing the contract is not a
     * reason to lose stored data. So this asserts what the migrations create, not what is synced --
     * confusing the two would invite somebody to delete a table from the schema on the strength of a
     * client-facing decision.
     */
    @Test
    fun migrationCreatesEveryTableTheSchemaShips() {
        val tables = tableNames()
        // Twelve tables, which is every one the migrations create and none they drop. `V1` creates
        // fourteen; `V5` added `spend_assignments` and rebuilt `family_state` as `family_state_v5`
        // before dropping the original and renaming, so that swap left the count at fifteen; `V8`
        // then dropped `family_state`, `period_limits` and `spend_assignments` together, because the
        // pool, the split and the spend requests are no longer part of the contract.
        listOf(
            "families", "members", "invites", "member_tokens",
            "transactions", "archived_transactions", "budget_periods",
            "saved_categories", "saved_tags", "recurring_templates", "savings_goals",
            "family_settings",
        ).forEach { assertTrue(it in tables, "missing table $it") }
        listOf("family_state", "period_limits", "spend_assignments").forEach {
            assertTrue(it !in tables, "V8 should have dropped $it")
        }
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
            "saved_categories", "saved_tags", "recurring_templates", "savings_goals",
            "family_settings",
        )
        val unprotected = mutableListOf<String>()
        tables.forEach { table ->
            if (table !in rowLevelSecurityEnabled()) unprotected.add(table)
        }
        assertEquals(emptyList(), unprotected, "tables reachable through the public api")
    }

    @Test
    fun savedNamesAreIndexedPerFamilyAndAreNotUnique() {
        val indexes = indexDefinitions()

        listOf("saved_categories", "saved_tags").forEach { table ->
            val index = assertNotNull(
                indexes.filter { it.first.startsWith("${table}_") }
                    .firstOrNull { it.second.contains("(family_id, name)") },
                "$table has no index on (family_id, name) among ${indexes.map { it.first }}",
            )
            assertTrue(
                !index.second.contains("UNIQUE"),
                "${table} name must stay non-unique so two members can save the same name",
            )
        }
    }

    @Test
    fun theBaselineVersionNeverSkipsAShippedMigration() {
        val versions = shippedMigrationVersions()

        assertTrue(versions.isNotEmpty(), "no migrations found under db/migration on the classpath")
        val newest = versions.last()
        assertTrue(
            BASELINE_VERSION.toInt() < newest,
            "V$newest is the newest shipped migration and BASELINE_VERSION is $BASELINE_VERSION; an " +
                "adopted schema would be marked at $BASELINE_VERSION, so V$newest would never run on it",
        )
    }

    @Test
    fun aPreExistingSchemaIsAdoptedRatherThanRecreated() {
        // Its own instance, not the shared TestDatabase one: this test needs a schema with no
        // flyway_schema_history at all, and it must not pollute the other suites' database.
        val instance = startEmbeddedPostgres()
        try {
            // Stands in for a database that already carries every shipped table but lost its history
            // table. Replaying V1 on top of them would die on its first `create table`.
            listOf("V1", "V2", "V3").forEach { applyMigration(instance, it) }

            migrate(instance.dataSource)

            instance.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select version, type from flyway_schema_history").use { rows ->
                        assertTrue(rows.next(), "migrate wrote no schema history")
                        assertEquals(BASELINE_VERSION, rows.getString(1))
                        assertEquals("BASELINE", rows.getString(2))
                    }
                }
            }

            // Adoption only skips migrations up to the baseline. V4 is newer, so it must still run:
            // baselining at V4 would mark it applied without ever creating its indexes.
            assertTrue(
                indexDefinitions(instance.dataSource).any { it.second.contains("(family_id, name)") },
                "V4 never ran on the adopted schema, so its indexes are missing",
            )
        } finally {
            instance.close()
        }
    }

    /**
     * The adoption guard, pinned.
     *
     * [FAMILY_TABLES] decides which schemas are safe to baseline at [BASELINE_VERSION], so it has to
     * be exactly the tables created up to and including that version and nothing newer. `V5` adds
     * `spend_assignments`; adding it here breaks adoption for every real V1-V3 database, because those
     * schemas legitimately lack it and `aPreExistingSchemaIsAdoptedRatherThanRecreated` then fails
     * with `Found non-empty schema(s) "public" but no schema history table`. Deriving the expected set
     * from the migration SQL rather than restating it means the next table added to the schema cannot
     * be added here by accident: this fails the day someone does, instead of the adoption path.
     */
    @Test
    fun adoptionBaselineTablesAreExactlyTheTablesUpToTheBaselineVersion() {
        val createdUpToBaseline = (1..BASELINE_VERSION.toInt())
            .flatMap { version ->
                CREATE_TABLE.findAll(migrationSql("V$version")).map { it.groupValues[1].lowercase() }.toList()
            }
            .toSet()

        assertEquals(
            createdUpToBaseline,
            FAMILY_TABLES.toSet(),
            "FAMILY_TABLES must match the tables created by V1..V$BASELINE_VERSION, nothing more",
        )
        assertTrue(
            "spend_assignments" !in FAMILY_TABLES,
            "spend_assignments arrives in V5, after the baseline, so an adopted V1-V3 schema lacks it",
        )
    }

    @Test
    fun aSchemaMissingFamilyTablesIsNotAdopted() {
        val instance = startEmbeddedPostgres()
        try {
            instance.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("create table something_else (id uuid primary key)")
                }
            }

            // No family tables, so this is not a family-sync database that lost its history table.
            // Adopting it would mark it at BASELINE_VERSION and leave it permanently missing
            // thirteen tables with nothing in the logs to explain why. Refusing to migrate is the
            // loud, correct outcome: the container never serves traffic and the reason is in the log.
            val failure = assertThrows(FlywayException::class.java) { migrate(instance.dataSource) }
            assertTrue(
                failure.message.orEmpty().contains("no schema history table"),
                "expected Flyway to refuse a non-empty schema it cannot adopt, got: ${failure.message}",
            )
            assertTrue(
                "families" !in tableNames(instance.dataSource),
                "the unrecognised schema was migrated over anyway",
            )
        } finally {
            instance.close()
        }
    }

    private fun migrationSql(version: String): String {
        val file = migrationFile(version)
        return requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$file")) {
            "db/migration/$file is not on the test classpath"
        }.bufferedReader().use { it.readText() }
    }

    private fun applyMigration(instance: EmbeddedPostgresInstance, version: String) {
        val file = migrationFile(version)
        val sql = requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$file")) {
            "db/migration/$file is not on the test classpath"
        }.bufferedReader().use { it.readText() }
        instance.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }
    }

    private fun migrationFile(version: String): String {
        val location = requireNotNull(javaClass.classLoader.getResource("db/migration")) {
            "db/migration is not on the test classpath"
        }
        val prefix = "${version}__"
        return File(location.toURI()).listFiles().orEmpty()
            .map { it.name }
            .firstOrNull { it.startsWith(prefix) }
            ?: error("no migration file found for $version")
    }

    private fun shippedMigrationVersions(): List<Int> {
        val location = requireNotNull(javaClass.classLoader.getResource("db/migration")) {
            "db/migration is not on the test classpath"
        }
        assertEquals("file", location.protocol, "expected db/migration to be an unpacked directory")
        val files = File(location.toURI()).listFiles().orEmpty()
        return files
            .map { it.name }
            .filter { it.startsWith("V") && it.endsWith(".sql") }
            .map { it.substringBefore("__").drop(1).toInt() }
            .sorted()
    }

    private fun indexDefinitions(dataSource: DataSource = TestDatabase.dataSource): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select indexname, indexdef from pg_indexes where schemaname = 'public'"
                ).use { rows ->
                    while (rows.next()) found.add(rows.getString(1) to rows.getString(2))
                }
            }
        }
        return found
    }

    private fun tableNames(dataSource: DataSource = TestDatabase.dataSource): Set<String> {
        val names = mutableSetOf<String>()
        dataSource.connection.use { connection ->
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) names.add(rs.getString("TABLE_NAME").lowercase())
            }
        }
        return names
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
