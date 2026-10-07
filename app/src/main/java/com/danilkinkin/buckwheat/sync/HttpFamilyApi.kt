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

private const val FAMILY_CONNECT_TIMEOUT_MS = 15_000
private const val FAMILY_READ_TIMEOUT_MS = 30_000

data class FamilyCredentials(
    val familyId: String,
    val memberId: String,
    val token: String,
    val joinCode: String,
)

data class WhoAmI(
    val memberId: String,
    val familyId: String,
    val displayName: String,
)

data class FamilyMember(
    val id: String,
    val displayName: String,
    val departed: Boolean,
    val joinedAt: String,
)

interface FamilyApi {
    suspend fun createFamily(displayName: String): FamilyCredentials
    suspend fun joinFamily(code: String, displayName: String): FamilyCredentials
    suspend fun whoami(token: String): WhoAmI
    suspend fun members(token: String): List<FamilyMember>
    suspend fun leave(token: String): Boolean = false
}

class HttpFamilyApi(private val baseUrl: String) : FamilyApi {

    override suspend fun createFamily(displayName: String): FamilyCredentials =
        decodeCredentials(
            post(familyEndpoint(baseUrl, "create"), null, JSONObject().put("displayName", displayName))
        )

    override suspend fun joinFamily(code: String, displayName: String): FamilyCredentials =
        decodeCredentials(
            post(
                familyEndpoint(baseUrl, "join"),
                null,
                JSONObject().put("code", code).put("displayName", displayName),
            )
        )

    override suspend fun whoami(token: String): WhoAmI =
        decodeWhoAmI(post(familyEndpoint(baseUrl, "whoami"), token, JSONObject()))

    override suspend fun members(token: String): List<FamilyMember> =
        decodeMembers(get(familyEndpoint(baseUrl, "members"), token))

    override suspend fun leave(token: String): Boolean = try {
        post(familyEndpoint(baseUrl, "leave"), token, JSONObject())
        true
    } catch (_: IOException) {
        false
    }

    private suspend fun post(url: String, token: String?, body: JSONObject): String =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "POST"
                conn.connectTimeout = FAMILY_CONNECT_TIMEOUT_MS
                conn.readTimeout = FAMILY_READ_TIMEOUT_MS
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token != null) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                conn.doOutput = true
                conn.connect()

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = runCatching {
                        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    throw IOException("family HTTP $code ${errorCodeOf(text) ?: "unknown"}")
                }

                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: "family request failed", e)
            } finally {
                connection?.disconnect()
            }
        }

    private suspend fun get(url: String, token: String?): String =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "GET"
                conn.connectTimeout = FAMILY_CONNECT_TIMEOUT_MS
                conn.readTimeout = FAMILY_READ_TIMEOUT_MS
                conn.useCaches = false
                if (token != null) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                conn.connect()

                val code = conn.responseCode
                if (code !in 200..299) {
                    val text = runCatching {
                        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    throw IOException("family HTTP $code ${errorCodeOf(text) ?: "unknown"}")
                }

                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message ?: "family request failed", e)
            } finally {
                connection?.disconnect()
            }
        }
}

internal fun familyEndpoint(baseUrl: String, action: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return "$trimmed/v1/family/$action"
}

internal fun decodeCredentials(body: String): FamilyCredentials {
    val json = jsonOrThrow(body, "family response")
    return FamilyCredentials(
        familyId = json.getString("familyId"),
        memberId = json.getString("memberId"),
        token = json.getString("token"),
        joinCode = json.optString("joinCode", ""),
    )
}

internal fun decodeWhoAmI(body: String): WhoAmI {
    val json = jsonOrThrow(body, "whoami response")
    return WhoAmI(
        memberId = json.getString("memberId"),
        familyId = json.getString("familyId"),
        displayName = json.getString("displayName"),
    )
}

/**
 * `{"members":[{"id":..,"displayName":..,"departed":..,"joinedAt":..}]}`. An element without an id or a
 * display name is dropped rather than thrown away with it, because one unusable member must not hide
 * the rest of the roster: this list is what every transaction's `memberId` is attributed against.
 */
internal fun decodeMembers(body: String): List<FamilyMember> {
    val json = jsonOrThrow(body, "members response")
    val array = json.optJSONArray("members") ?: JSONArray()
    return buildList {
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optNullableString("id")?.takeIf { it.isNotBlank() } ?: continue
            val displayName = entry.optNullableString("displayName")?.takeIf { it.isNotBlank() } ?: continue
            add(
                FamilyMember(
                    id = id,
                    displayName = displayName,
                    departed = entry.optBoolean("departed", false),
                    joinedAt = entry.optString("joinedAt"),
                )
            )
        }
    }
}

private fun jsonOrThrow(body: String, what: String): JSONObject = try {
    JSONObject(body)
} catch (e: Exception) {
    throw IOException("$what was not json", e)
}
