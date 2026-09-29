package family.sync

import family.sync.family.ApiException
import family.sync.family.familyRoutes
import family.sync.sync.syncRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import javax.sql.DataSource

fun main() {
    val config = loadConfig(System.getenv())
    val dataSource = createDataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
    migrate(dataSource)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        familySyncModule(dataSource)
    }.start(wait = true)
}

fun Application.familySyncModule(dataSource: DataSource) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ApiException> { call, failure ->
            call.respond(failure.status, mapOf("error" to failure.code))
        }
    }
    configureHealth()
    familyRoutes(dataSource)
    syncRoutes(dataSource)
}
