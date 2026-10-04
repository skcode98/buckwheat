package family.sync.sync

import family.sync.auth.Principal
import family.sync.auth.TokenService
import family.sync.family.BadRequestException
import family.sync.family.SecuritySettings
import family.sync.family.UnauthorizedException
import family.sync.family.receiveTextLimited
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.sql.DataSource

fun Application.syncRoutes(
    dataSource: DataSource,
    security: SecuritySettings = SecuritySettings.fromEnv(),
) {
    val tokenService = TokenService(dataSource, security.tokenLifetime)
    val store = SyncStore(dataSource)

    routing {
        post("/v1/sync") {
            val principal = tokenService.requirePrincipal(call.requestHeaderToken())
            // A sync batch carries up to MAX_CHANGES entries, so the whole request can be large.
            // receiveTextLimited streams at most maxBytes+1 and rejects 413, which also covers a
            // chunked body that declares no Content-Length — the global plugin only sees a
            // declared length, so it cannot close that case on its own.
            val body = call.receiveTextLimited(security.maxRequestBytes).parseBody()
            val changes = body.changeObjects().map { it.toPushChange() }
            val outcome = store.sync(
                familyId = principal.familyId,
                memberId = principal.memberId,
                cursor = body.requiredLong("cursor"),
                changes = changes,
                since = body.optionalLong("since"),
            )
            call.respond(HttpStatusCode.OK, outcome.toResponse())
        }
    }
}

private fun ApplicationCall.requestHeaderToken(): String? {
    val header = request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim()
}

private fun TokenService.requirePrincipal(token: String?): Principal =
    token?.let { verify(it) } ?: throw UnauthorizedException("unauthenticated")

private fun String.parseBody(): JsonObject {
    val element = try {
        Json.parseToJsonElement(this)
    } catch (failure: SerializationException) {
        throw BadRequestException("body_invalid")
    }
    return element as? JsonObject ?: throw BadRequestException("body_invalid")
}

private fun JsonObject.toPushChange(): PushChange = PushChange(
    table = requiredText("table"),
    id = requiredText("id"),
    version = requiredInt("version"),
    updatedAt = requiredLong("updatedAt"),
    deletedAt = optionalLong("deletedAt"),
    payload = requiredPayload("payload"),
)

private fun JsonObject.changeObjects(): List<JsonObject> {
    val element = this["changes"]
    if (element == null || element is JsonNull) return emptyList()
    val array = element as? JsonArray ?: throw BadRequestException("changes_invalid")
    return array.map { it as? JsonObject ?: throw BadRequestException("changes_invalid") }
}

private fun JsonObject.requiredInt(field: String): Int {
    val value = requiredLong(field)
    if (value < 0 || value > Int.MAX_VALUE) throw BadRequestException("${field}_invalid")
    return value.toInt()
}

private fun JsonObject.requiredText(field: String): String {
    val value = this[field]
    if (value !is JsonPrimitive || !value.isString || value.content.isBlank()) {
        throw BadRequestException("${field}_required")
    }
    return value.content
}

private fun JsonObject.requiredLong(field: String): Long =
    this[field]?.jsonPrimitive?.content?.toLongOrNull() ?: throw BadRequestException("${field}_required")

private fun JsonObject.optionalLong(field: String): Long? {
    val element = this[field] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.content.toLongOrNull() ?: throw BadRequestException("${field}_invalid")
}

private fun JsonObject.requiredPayload(field: String): String {
    val element = this[field] ?: throw BadRequestException("${field}_required")
    if (element !is JsonObject) throw BadRequestException("payload_invalid")
    return element.toString()
}

private fun SyncOutcome.toResponse(): JsonObject = buildJsonObject {
    put("cursor", cursor)
    put("hasMore", hasMore)
    putJsonArray("accepted") {
        accepted.forEach { add(buildJsonObject { put("table", it.table); put("id", it.id) }) }
    }
    putJsonArray("records") {
        records.forEach { record ->
            add(
                buildJsonObject {
                    put("table", record.table)
                    put("id", record.id)
                    put("seq", record.seq)
                    put("updatedAt", record.updatedAt)
                    put("version", record.version)
                    put("payload", Json.parseToJsonElement(record.payload))
                    if (record.deletedAt != null) put("deletedAt", record.deletedAt)
                    if (record.memberId != null) put("memberId", record.memberId)
                }
            )
        }
    }
    putJsonArray("conflicts") {
        rejected.forEach { rejection ->
            add(
                buildJsonObject {
                    put("table", rejection.table)
                    put("id", rejection.id)
                    put("reason", rejection.reason.wire)
                    if (rejection.wonByMemberId != null) put("wonByMemberId", rejection.wonByMemberId)
                }
            )
        }
    }
}
