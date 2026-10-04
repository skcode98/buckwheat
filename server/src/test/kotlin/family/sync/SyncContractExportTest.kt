package family.sync

import family.sync.sync.MAX_CHANGES
import family.sync.sync.MAX_PULL_ROWS
import family.sync.sync.RejectReason
import family.sync.sync.SyncTables
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the generated contract still agrees with a plain text scrape of `SyncStore.kt`.
 *
 * The generated file is what the Node service and the Android app are both wired to, so the one
 * thing that must never happen is the two drifting apart. The scrape below is a deliberate
 * reimplementation of `SyncPayloadContractTest`'s parsing rather than a call into it: two
 * independent readings of the same source that agree is evidence, one reading compared with itself
 * is not.
 */
class SyncContractExportTest {

    @Test
    fun theGeneratedContractMatchesAScrapeOfTheSource() {
        val scraped = scrapeSyncStore(File(serverDir(), "src/main/kotlin/family/sync/sync/SyncStore.kt"))

        assertEquals(
            "tables scraped from SyncStore.kt do not match SyncTables.ALL",
            SyncTables.ALL.map { it.name },
            scraped.keys.toList(),
        )
        scraped.forEach { (table, keys) ->
            val spec = SyncTables.ALL.first { it.name == table }
            assertEquals("payload keys for $table", keys, spec.columns.map { it.key })
        }
    }

    @Test
    fun theRenderedContractIsStableAcrossRuns() {
        val serverDir = serverDir()
        assertEquals(SyncContractExport.render(serverDir), SyncContractExport.render(serverDir))
    }

    /**
     * The rendered contract must describe every table the server can actually push.
     *
     * This is the check that belongs here, and the obvious one -- "is the committed JSON stale" -- is
     * deliberately NOT, because there is no committed JSON in this repository: `.kilo/` is gitignored
     * (`.gitignore:25`) and the generated file is a local build artefact. The copy a consumer actually
     * reads lives in a separate Node repository at `contract/sync-contract.json`, so a staleness guard
     * here would either fail on a fresh clone or quietly compare a file nobody ships.
     *
     * Staleness is instead caught where it bites: `SyncPayloadContractTest` on the client parses this
     * server's source directly, so a table or column added here without a matching client entry fails
     * a build. The Node copy is refreshed by hand from `generateSyncContract` output.
     */
    @Test
    fun theRenderedContractDescribesEveryTableTheServerAccepts() {
        val contract = Json.parseToJsonElement(SyncContractExport.render(serverDir())).jsonObject
        val tables = contract["tables"]!!.jsonArray.map { it.jsonObject }
        val names = tables.map { it["name"]!!.jsonPrimitive.content }

        assertEquals(
            "the contract does not list every table in SyncTables.ALL",
            SyncTables.ALL.map { it.name }.toSet(),
            names.toSet(),
        )
    }

    /**
     * The contract is a table list, so a consumer building its own definitions from this has to be
     * told exactly what is left. Anything named here that the server no longer syncs would be built and
     * then refused, and anything missing would fail as a silent push error rather than as an error
     * here.
     */
    @Test
    fun theContractCarriesExactlyOneTable() {
        val tables = contract().jsonObject["tables"]!!.jsonArray
        assertEquals(1, tables.size)
        val columns = tables.single().jsonObject["columns"]!!.jsonArray
            .map { it.jsonObject["key"]!!.jsonPrimitive.content }

        assertEquals(listOf("type", "value", "spentAt", "comment", "category"), columns)
    }

    /** A consumer that has not regenerated its copy keeps building the old nine-table model. */
    @Test
    fun theContractIsAtSchemaVersionThree() {
        assertEquals(3, contract().jsonObject["schemaVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun everyErrorCodeLooksLikeAnErrorCode() {
        val contract = Json.parseToJsonElement(SyncContractExport.render(serverDir())).jsonObject

        val codes = contract["errorCodes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("no error codes were scraped", codes.isNotEmpty())
        codes.forEach {
            assertTrue(
                "`$it` is not an error code; comments were scraped as if they were throw sites",
                Regex("[a-z0-9_]+").matches(it),
            )
        }
        listOf("unauthenticated", "payload_incomplete", "payload_invalid", "internal_error").forEach {
            assertTrue("missing error code $it", it in codes)
        }
    }

    @Test
    fun theContractCarriesTheLimitsAndRejectionReasonsTheServerUses() {
        val contract = Json.parseToJsonElement(SyncContractExport.render(serverDir())).jsonObject

        assertEquals(
            RejectReason.entries.map { it.wire },
            contract["rejectReasons"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val limits = contract["limits"]!!.jsonObject
        assertEquals(MAX_CHANGES, limits["maxChanges"]!!.jsonPrimitive.content.toInt())
        assertEquals(MAX_PULL_ROWS, limits["maxPullRows"]!!.jsonPrimitive.content.toInt())

        val tables = contract["tables"]!!.jsonArray
        assertEquals(SyncTables.ALL.size, tables.size)
        tables.forEachIndexed { index, element ->
            val spec = SyncTables.ALL[index]
            val table = element.jsonObject
            assertEquals(spec.name, table["name"]!!.jsonPrimitive.content)
            assertEquals(spec.hasMember, table["hasMember"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(
                spec.columns.map { it.key },
                table["columns"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content },
            )
        }
    }

    /** The rendered contract, parsed once per assertion that needs it. */
    private fun contract(): JsonObject =
        Json.parseToJsonElement(SyncContractExport.render(serverDir())).jsonObject

    /** Mirrors `SyncPayloadContractTest.serverSpec()` exactly, regexes included. */
    private fun scrapeSyncStore(file: File): Map<String, List<String>> {
        val columns = linkedMapOf<String, MutableList<String>>()
        var insideAll = false
        var awaitingTableName = false
        file.readLines().forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith(SPEC_START) -> insideAll = true
                !insideAll -> Unit
                trimmed.startsWith(TABLE_SPEC) -> awaitingTableName = true
                awaitingTableName -> {
                    val name = TABLE_NAME.find(line)
                        ?: throw AssertionError("TableSpec without a name in ${file.name}: $line")
                    columns.getOrPut(name.groupValues[1]) { mutableListOf() }
                    awaitingTableName = false
                }
                trimmed.startsWith(PAYLOAD_COLUMN) -> {
                    val key = COLUMN_KEY.find(line)
                        ?: throw AssertionError("PayloadColumn without a key in ${file.name}: $line")
                    columns.values.last().add(key.groupValues[1])
                }
            }
        }
        return columns
    }

    /**
     * The test JVM's working directory is the Gradle root project, not `server/`, so both shapes are
     * accepted rather than assuming one.
     */
    private fun serverDir(): File {
        val start = File(requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" })
        for (root in generateSequence(start) { it.parentFile }) {
            for (marker in MARKERS) {
                val file = File(root, marker)
                if (file.isFile) {
                    var dir = file.parentFile
                    repeat(marker.count { it == '/' }) { dir = dir.parentFile }
                    return dir
                }
            }
        }
        throw AssertionError("no ancestor of $start contains any of $MARKERS")
    }

    private companion object {
        val MARKERS = listOf(
            "src/main/kotlin/family/sync/sync/SyncStore.kt",
            "server/src/main/kotlin/family/sync/sync/SyncStore.kt",
        )
        const val SPEC_START = "val ALL: List<TableSpec>"
        const val TABLE_SPEC = "TableSpec("
        const val PAYLOAD_COLUMN = "PayloadColumn("
        val TABLE_NAME = Regex("""name = "([^"]+)"""")
        val COLUMN_KEY = Regex("""PayloadColumn\("([^"]+)"""")
    }
}