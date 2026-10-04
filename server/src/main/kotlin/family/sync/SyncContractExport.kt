package family.sync

import family.sync.family.DEFAULT_MAX_REQUEST_BYTES
import family.sync.sync.MAX_CHANGES
import family.sync.sync.MAX_NUMERIC_INTEGER_DIGITS
import family.sync.sync.MAX_NUMERIC_LENGTH
import family.sync.sync.MAX_NUMERIC_SCALE
import family.sync.sync.MAX_PULL_ROWS
import family.sync.sync.MAX_TEXT_LENGTH
import family.sync.sync.PayloadColumn
import family.sync.sync.RejectReason
import family.sync.sync.SyncTables
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Emits `sync-contract.json` from live Kotlin data rather than from a hand-maintained copy.
 *
 * The payload key lists are the highest-risk data in the Android<->server contract: a single wrong
 * key corrupts rows silently rather than failing loudly. Generating the file removes the
 * transcription step, and `SyncContractExportTest` proves the generated file still agrees with what
 * `SyncPayloadContractTest` extracts from the source today.
 *
 * The Node service in `buckwheat-sync` keeps its own copy at `contract/sync-contract.json` and
 * builds `src/domain/tables.js` from it.
 */
object SyncContractExport {

    /** Bumped whenever the emitted shape changes, so a consumer can detect a stale copy. */
    const val SCHEMA_VERSION = 3

    const val DEFAULT_OUTPUT = "../.kilo/sync-contract.json"

    private const val RUNTIME_SOURCE_DIR = "src/main/kotlin/family/sync"

    fun contract(serverDir: File): JsonElement {
        val files = runtimeSources(serverDir)
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("source", "family.sync.sync.SyncTables.ALL")
            put("tables", buildJsonArray { SyncTables.ALL.forEach { add(table(it.name, it.hasMember, it.columns)) } })
            put("rejectReasons", strings(RejectReason.entries.map { it.wire }))
            put("errorCodes", strings(errorCodes(files)))
            put("templatedErrorCodes", strings(TEMPLATED_ERROR_CODES))
            put("responseFields", responseFields(files))
            put("limits", limits())
        }
    }

    fun render(serverDir: File): String {
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        return pretty.encodeToString(JsonElement.serializer(), contract(serverDir)) + "\n"
    }

    fun runtimeSources(serverDir: File): List<File> =
        File(serverDir, RUNTIME_SOURCE_DIR).walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun table(name: String, hasMember: Boolean, columns: List<PayloadColumn>) =
        buildJsonObject {
            put("name", name)
            put("hasMember", hasMember)
            put("columns", buildJsonArray { columns.forEach { add(column(it)) } })
        }

    private fun column(column: PayloadColumn) = buildJsonObject {
        put("key", column.key)
        put("column", column.column)
        put("type", column.type.name)
        put("cast", column.type.cast)
        put("nullable", column.nullable)
        column.allowedValues?.let { put("allowedValues", strings(it.toList())) }
    }

    /**
     * Every error code the server can actually emit, scraped from the throw sites rather than
     * listed by hand, so a new throw site cannot go unnoticed.
     *
     * Comments are stripped first: this file documents the scraper using a throw site as its
     * example, and prose about a code is not a code the server can return.
     */
    private fun errorCodes(files: List<File>): List<String> {
        val codes = sortedSetOf<String>()
        files.forEach { file ->
            LITERAL_CODE.findAll(codeOf(file.readText())).forEach { codes.add(it.groupValues[1]) }
        }
        codes.addAll(FIXED_CODES)
        return codes.toList()
    }

    private fun codeOf(source: String): String =
        source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " ")

    /**
     * Field names of each wire shape, scraped from the `put("name", ...)` calls that build them, so
     * the list cannot drift from the responder that produces them.
     */
    private fun responseFields(files: List<File>): JsonElement {
        val syncRoute = codeOf(files.first { it.name == "SyncRoutes.kt" }.readText())
        val accepted = syncRoute.substringAfter("putJsonArray(\"accepted\")").substringBefore("putJsonArray(\"records\")")
        val records = syncRoute.substringAfter("putJsonArray(\"records\")").substringBefore("putJsonArray(\"conflicts\")")
        val conflicts = syncRoute.substringAfter("putJsonArray(\"conflicts\")")
        return buildJsonObject {
            put("sync", strings(TOP_LEVEL_FIELDS))
            put("acceptedEntry", putNames(accepted))
            put("record", putNames(records))
            put("conflictEntry", putNames(conflicts))
        }
    }

    private fun putNames(source: String): JsonElement = buildJsonArray {
        PUT_NAME.findAll(source).forEach { add(JsonPrimitive(it.groupValues[1])) }
    }

    private fun strings(values: List<String>): JsonElement = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun limits(): JsonElement = buildJsonObject {
        put("maxChanges", MAX_CHANGES)
        put("maxPullRows", MAX_PULL_ROWS)
        put("maxTextLength", MAX_TEXT_LENGTH)
        put("maxNumericIntegerDigits", MAX_NUMERIC_INTEGER_DIGITS)
        put("maxNumericScale", MAX_NUMERIC_SCALE)
        put("maxNumericLength", MAX_NUMERIC_LENGTH)
        put("maxRequestBytes", DEFAULT_MAX_REQUEST_BYTES)
        put("maxPoolSize", MAX_POOL_SIZE)
    }

    private val LITERAL_CODE = Regex("""(?:Exception|ApiException)\("([a-z0-9_]+)"\)""")
    private val PUT_NAME = Regex("""put\("([A-Za-z][A-Za-z0-9]*)"""")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    /** Codes no literal throw produces; listed so the contract lists every code a client can see. */
    private val FIXED_CODES = listOf("internal_error")

    private val TOP_LEVEL_FIELDS = listOf("cursor", "hasMore", "accepted", "records", "conflicts")

    /**
     * Codes assembled at runtime from a request field name, so only the suffix is part of the
     * contract.
     */
    private val TEMPLATED_ERROR_CODES = listOf("{field}_required", "{field}_invalid", "{field}_too_long")
}

fun main() {
    val serverDir = System.getProperty("user.dir").takeIf { File(it, "src/main/kotlin").isDirectory }
        ?: error("run this from the server module directory")
    val output = File(System.getenv("SYNC_CONTRACT_OUT") ?: File(serverDir, SyncContractExport.DEFAULT_OUTPUT).path)
    output.parentFile?.mkdirs()
    output.writeText(SyncContractExport.render(File(serverDir)))
    println("wrote ${output.absolutePath}")
}