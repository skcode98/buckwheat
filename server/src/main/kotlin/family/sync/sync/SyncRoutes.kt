package family.sync.sync

import family.sync.auth.Principal
import family.sync.auth.TokenService
import family.sync.family.BadRequestException
import family.sync.family.UnauthorizedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.sql.DataSource

fun Application.syncRoutes(dataSource: DataSource) {
    val tokenService = TokenService(dataSource)
    val store = SyncStore(dataSource)

    routing {
        post("/v1/sync") {
            val principal = tokenService.requirePrincipal(call.requestHeaderToken())
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val changes = body.changesOrEmpty().map { it.jsonObject.toPushChange() }
            val outcome = store.sync(
                familyId = principal.familyId,
                memberId = principal.memberId,
                cursor = body.requiredLong("cursor"),
                changes = changes,
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

private fun JsonObject.toPushChange(): PushChange = PushChange(
    table = requiredText("table"),
    id = requiredText("id"),
    version = requiredLong("version").toInt(),
    updatedAt = requiredLong("updatedAt"),
    deletedAt = optionalLong("deletedAt"),
    payload = requiredPayload("payload"),
)

private fun JsonObject.changesOrEmpty(): JsonArray {
    val element = this["changes"]
    if (element == null || element is JsonNull) return JsonArray(emptyList())
    return element.jsonArray
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
    putJsonArray("accepted") { accepted.forEach { add(it) } }
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
                    put("wonByMemberId", rejection.wonByMemberId)
                }
            )
        }
    }
}
