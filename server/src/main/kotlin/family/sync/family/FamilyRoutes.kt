package family.sync.family

import family.sync.auth.Principal
import family.sync.auth.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import javax.sql.DataSource

fun Application.familyRoutes(dataSource: DataSource) {
    val tokenService = TokenService(dataSource)
    val store = FamilyStore(dataSource, tokenService)
    val parser = Json

    routing {
        route("/v1/family") {
            post("/create") {
                val displayName = parser.readText(call.receiveText(), "displayName")
                val credentials = store.createFamily(displayName)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "familyId" to credentials.familyId,
                        "memberId" to credentials.memberId,
                        "token" to credentials.token,
                    ),
                )
            }

            post("/invite") {
                val principal = tokenService.requirePrincipal(call.requestHeaderToken())
                call.respond(HttpStatusCode.OK, store.mintInvite(principal).let {
                    mapOf("code" to it.code, "expiresAt" to it.expiresAt)
                })
            }

            post("/join") {
                val body = call.receiveText().parseObjectBody()
                val credentials = store.redeemInvite(
                    code = body.requiredText("code"),
                    displayName = body.requiredText("displayName"),
                )
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "familyId" to credentials.familyId,
                        "memberId" to credentials.memberId,
                        "token" to credentials.token,
                    ),
                )
            }

            post("/whoami") {
                val principal = tokenService.requirePrincipal(call.requestHeaderToken())
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "memberId" to principal.memberId,
                        "familyId" to principal.familyId,
                        "displayName" to store.displayNameOf(principal.memberId),
                    ),
                )
            }

            post("/members") {
                val principal = tokenService.requirePrincipal(call.requestHeaderToken())
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("members" to store.familyDisplayNames(principal.familyId)),
                )
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requestHeaderToken(): String? {
    val header = request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ")) return null
    return header.removePrefix("Bearer ").trim()
}

private fun TokenService.requirePrincipal(token: String?): Principal =
    token?.let { verify(it) } ?: throw UnauthorizedException("unauthenticated")

private fun String.parseObjectBody(): JsonObject {
    val element = try {
        Json.parseToJsonElement(this)
    } catch (failure: IllegalArgumentException) {
        throw BadRequestException("body_invalid")
    }
    return element as? JsonObject ?: throw BadRequestException("body_invalid")
}

private fun Json.readText(body: String, field: String): String =
    body.parseObjectBody().requiredText(field)

private fun kotlinx.serialization.json.JsonObject.requiredText(field: String): String {
    val value = this[field]
    if (value !is JsonPrimitive || !value.isString) {
        throw BadRequestException("${field}_required")
    }
    if (value.content.isBlank()) throw BadRequestException("${field}_required")
    return value.content
}
