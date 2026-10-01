package family.sync

import family.sync.family.ApiException
import family.sync.family.SecuritySettings
import family.sync.family.familyRoutes
import family.sync.family.installRequestBodyLimit
import family.sync.sync.syncRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import javax.sql.DataSource

fun main() {
    val config = loadConfig(System.getenv())
    println("connecting to ${config.redactedTarget()}")
    val dataSource = createDataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
    migrate(dataSource)
    println("database ready; serving on 0.0.0.0:${config.port}")
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        familySyncModule(dataSource, SecuritySettings.fromEnv(System.getenv()))
    }.start(wait = true)
}

/**
 * Row level security is enabled on every table with no policies (see
 * `V3__lock_down_public_access.sql`), which correctly denies Supabase's anon and
 * authenticated REST roles, but `DATABASE_USER` defaults to `postgres`, a role
 * with BYPASSRLS, so nothing stops this server's own statements through RLS.
 *
 * Every authorization decision is therefore made here in application code: tokens
 * are hashed and lifetime checked by `TokenService`, and each family query is
 * scoped by the `family_id` of the verified principal, never by RLS. Keep the
 * migration as it is; if this server ever connects as a non-superuser role, every
 * table needs real policies before it can read anything.
 */
fun Application.familySyncModule(
    dataSource: DataSource,
    settings: SecuritySettings = SecuritySettings.fromEnv(),
) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ApiException> { call, failure ->
            call.respond(failure.status, mapOf("error" to failure.code))
        }
        exception<Throwable> { call, failure ->
            call.application.log.error("unhandled failure on ${call.request.local.uri}", failure)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }
    installRequestBodyLimit(settings.maxRequestBytes)
    configureHealth()
    familyRoutes(dataSource, settings)
    syncRoutes(dataSource, settings)
}
