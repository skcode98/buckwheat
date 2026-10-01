package family.sync.family

import family.sync.auth.Principal
import family.sync.auth.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.sql.DataSource

private const val MAX_INVITE_CODE_LENGTH = 16

fun Application.familyRoutes(
    dataSource: DataSource,
    settings: SecuritySettings = SecuritySettings.fromEnv(),
) {
    val tokenService = TokenService(dataSource, settings.tokenLifetime)
    val store = FamilyStore(dataSource, tokenService, settings.maxOutstandingInvites)
    val createLimiter = RateLimiter(settings.createRate, settings.clock)
    val joinLimiter = RateLimiter(settings.joinRate, settings.clock)
    val inviteLimiter = RateLimiter(settings.inviteRate, settings.clock)

    routing {
        route("/v1/family") {
            post("/create") {
                call.limit(createLimiter, "create")
                val body = call.receiveTextLimited(settings.maxRequestBytes).parseObjectBody()
                val credentials = store.createFamily(
                    body.requiredText("displayName", settings.maxDisplayNameLength),
                )
                call.respond(HttpStatusCode.OK, credentials.response())
            }

            post("/invite") {
                call.limit(inviteLimiter, "invite")
                val token = call.bearerToken() ?: throw UnauthorizedException("unauthenticated")
                val principal = tokenService.requirePrincipal(token)
                call.respond(HttpStatusCode.OK, store.mintInvite(principal).let {
                    mapOf("code" to it.code, "expiresAt" to it.expiresAt)
                })
            }

            post("/join") {
                call.limit(joinLimiter, "join")
                val body = call.receiveTextLimited(settings.maxRequestBytes).parseObjectBody()
                val credentials = store.redeemInvite(
                    code = body.requiredText("code", MAX_INVITE_CODE_LENGTH),
                    displayName = body.requiredText("displayName", settings.maxDisplayNameLength),
                )
                call.respond(HttpStatusCode.OK, credentials.response())
            }

            post("/leave") {
                val token = call.bearerToken() ?: throw UnauthorizedException("unauthenticated")
                val principal = tokenService.requirePrincipal(token)
                tokenService.revoke(token)
                store.leave(principal)
                call.respond(HttpStatusCode.OK, mapOf("left" to true))
            }

            post("/whoami") {
                val principal = tokenService.requirePrincipal(call.bearerToken())
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "memberId" to principal.memberId,
                        "familyId" to principal.familyId,
                        "displayName" to store.displayNameOf(principal.memberId).orEmpty(),
                    ),
                )
            }

            post("/members") { call.respondWithMembers(store, tokenService) }

            get("/members") { call.respondWithMembers(store, tokenService) }
        }
    }
}

private fun FamilyCredentials.response(): Map<String, String> = mapOf(
    "familyId" to familyId,
    "memberId" to memberId,
    "token" to token,
)

private suspend fun ApplicationCall.respondWithMembers(
    store: FamilyStore,
    tokenService: TokenService,
) {
    val principal = tokenService.requirePrincipal(bearerToken())
    // Built with kotlinx's JsonObject builder rather than a nested Map<String, Any>: the content
    // negotiator serialises by reified type, and `Any` has no serializer, so a nested map of maps
    // fails at runtime with a 500 instead of rendering. JsonObject is the same approach SyncRoutes
    // already uses for its nested payloads.
    val members = buildJsonObject {
        putJsonArray("members") {
            store.familyMembers(principal.familyId).forEach { member ->
                add(
                    buildJsonObject {
                        put("id", member.id)
                        put("displayName", member.displayName)
                        put("isOwner", member.isOwner)
                        put("joinedAt", member.joinedAt)
                    }
                )
            }
        }
    }
    respond(HttpStatusCode.OK, members)
}

private fun ApplicationCall.limit(limiter: RateLimiter, action: String) {
    if (!limiter.tryAcquire("$action|${clientAddress()}")) {
        throw TooManyRequestsException("rate_limited")
    }
}

// Render terminates every request behind its own proxy, so the socket peer is the proxy for every
// caller and a forwarded hop is the only useful rate-limit key. X-Forwarded-For is built by
// appending, so the leftmost entry is the one a client can forge at will and keying on it would let
// anyone reset their own bucket by sending a fresh value. The rightmost entry is the one the nearest
// trusted proxy observed, so that is the one used.
private fun ApplicationCall.clientAddress(): String {
    val forwarded = request.headers["X-Forwarded-For"]
        ?.split(',')
        ?.lastOrNull { it.isNotBlank() }
        ?.trim()
    if (!forwarded.isNullOrEmpty()) return forwarded
    return request.origin.remoteHost
}

private fun ApplicationCall.bearerToken(): String? {
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

private fun JsonObject.requiredText(field: String, maxLength: Int): String {
    val value = this[field]
    if (value !is JsonPrimitive || !value.isString) {
        throw BadRequestException("${field}_required")
    }
    if (value.content.isBlank()) throw BadRequestException("${field}_required")
    if (value.content.length > maxLength) throw BadRequestException("${field}_too_long")
    return value.content
}
