package com.danilkinkin.buckwheat.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val SYNC_CONNECT_TIMEOUT_MS = 15_000
private const val SYNC_READ_TIMEOUT_MS = 30_000

class HttpSyncClient(private val baseUrl: String) : SyncClient {

    override suspend fun sync(token: String, request: SyncRequest): SyncResponse =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val conn = URL(syncEndpoint(baseUrl)).openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "POST"
                conn.connectTimeout = SYNC_CONNECT_TIMEOUT_MS
                conn.readTimeout = SYNC_READ_TIMEOUT_MS
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.doOutput = true
                conn.connect()

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(encodeSyncRequest(request).toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val body = runCatching {
                        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    throw IOException("sync HTTP $code ${errorCodeOf(body) ?: "unknown"}")
                }

                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                decodeSyncResponse(body)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: "sync request failed", e)
            } finally {
                connection?.disconnect()
            }
        }
}

internal fun syncEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1/sync")) trimmed else "$trimmed/v1/sync"
}

internal fun encodeSyncRequest(request: SyncRequest): JSONObject {
    val changes = JSONArray()
    for (change in request.changes) {
        changes.put(
            JSONObject()
                .put("table", change.table)
                .put("id", change.id)
                .put("version", change.version)
                .put("updatedAt", change.updatedAt)
                .put("deletedAt", change.deletedAt ?: JSONObject.NULL)
                .put("payload", JSONObject(change.payload))
        )
    }
    return JSONObject()
        .put("cursor", request.cursor)
        .put("changes", changes)
}

internal fun decodeSyncResponse(body: String): SyncResponse {
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw IOException("sync response was not json", e)
    }

    val records = mutableListOf<WireRecord>()
    val recordArray = json.optJSONArray("records") ?: JSONArray()
    for (index in 0 until recordArray.length()) {
        val item = recordArray.getJSONObject(index)
        records.add(
            WireRecord(
                table = item.getString("table"),
                id = item.getString("id"),
                seq = item.getLong("seq"),
                updatedAt = item.getLong("updatedAt"),
                version = item.getInt("version"),
                deletedAt = optNullableLong(item, "deletedAt"),
                payload = item.optJSONObject("payload")?.toString() ?: "{}",
                memberId = optNullableString(item, "memberId"),
            )
        )
    }

    val accepted = decodeAccepted(json.optJSONArray("accepted") ?: JSONArray())

    val conflicts = mutableListOf<ConflictNotice>()
    val conflictArray = json.optJSONArray("conflicts") ?: JSONArray()
    for (index in 0 until conflictArray.length()) {
        val item = conflictArray.getJSONObject(index)
        conflicts.add(
            ConflictNotice(
                table = item.getString("table"),
                id = item.getString("id"),
                // Absent, not null: an omitted winner means no member owns this row, which is the
                // correct reading for the memberless tables the server shares with the whole family.
                wonByMemberId = optNullableString(item, "wonByMemberId"),
                reason = ConflictReason.fromWire(optNullableString(item, "reason")),
            )
        )
    }

    return SyncResponse(
        cursor = json.optLong("cursor", 0L),
        accepted = accepted,
        records = records,
        conflicts = conflicts,
        hasMore = json.optBoolean("hasMore", false),
    )
}

/**
 * `accepted` entries are `{"table":..,"id":..}` objects. A bare string is a legacy server that has not
 * been updated yet: an app update can reach a device before the server does, so such an entry is kept
 * with an empty table and matched by id alone. Discarding it instead would silently re-push a change the
 * server already stored, forever.
 */
internal fun decodeAccepted(array: JSONArray): List<RecordKey> {
    val accepted = mutableListOf<RecordKey>()
    for (index in 0 until array.length()) {
        when (val entry = array.opt(index)) {
            is JSONObject -> accepted.add(
                RecordKey(
                    table = optNullableString(entry, "table").orEmpty(),
                    id = optNullableString(entry, "id").orEmpty(),
                )
            )

            is String -> if (entry.isNotBlank()) accepted.add(RecordKey(table = "", id = entry))
            else -> Unit
        }
    }
    return accepted.filter { it.id.isNotBlank() }
}

internal fun errorCodeOf(body: String): String? {
    if (body.isBlank()) return null
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return null
    }
    if (!json.has("error") || json.isNull("error")) return null
    return json.optString("error").ifBlank { null }
}

private fun optNullableString(json: JSONObject, name: String): String? {
    if (!json.has(name) || json.isNull(name)) return null
    return json.optString(name)
}

private fun optNullableLong(json: JSONObject, name: String): Long? {
    if (!json.has(name) || json.isNull(name)) return null
    return json.optLong(name)
}
