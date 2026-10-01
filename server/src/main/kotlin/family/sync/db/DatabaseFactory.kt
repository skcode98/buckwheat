package family.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

const val MAX_POOL_SIZE = 5
const val CONNECTION_TIMEOUT_MS = 10_000L
const val MAX_LIFETIME_MS = 600_000L
const val KEEPALIVE_MS = 120_000L

fun createDataSource(jdbcUrl: String, user: String, password: String): DataSource {
    val hikari = HikariConfig()
    hikari.jdbcUrl = jdbcUrl
    hikari.username = user
    hikari.password = password
    hikari.maximumPoolSize = MAX_POOL_SIZE
    hikari.minimumIdle = 0
    hikari.connectionTimeout = CONNECTION_TIMEOUT_MS
    hikari.maxLifetime = MAX_LIFETIME_MS
    hikari.keepaliveTime = KEEPALIVE_MS
    return HikariDataSource(hikari)
}

fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate()
}
