package family.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

const val MAX_POOL_SIZE = 5
const val CONNECTION_TIMEOUT_MS = 10_000L
const val MAX_LIFETIME_MS = 600_000L
const val KEEPALIVE_MS = 120_000L

/** Hikari gives up on the very first connection when this is 1, which turns a momentary blip fatal. */
const val POOL_INIT_TIMEOUT_MS = 60_000L
const val POOL_INIT_ATTEMPTS = 5
const val POOL_INIT_BACKOFF_MS = 2_000L

/**
 * Version an existing schema is tagged with when it has no flyway_schema_history row.
 *
 * The database has to be adoptable: a deployment that already carries the tables but lost its history
 * table must not have V1 replayed on top of them, because V1 opens with `create table families` and dies
 * with "relation already exists". Tagging at this version marks every migration up to it as already
 * applied, so only migrations newer than this one ever run against an adopted database.
 *
 * This is deliberately the newest migration that has actually run against a live database, NOT the
 * newest migration shipped. V4 (the (family_id, name) indexes) is not on any live database yet, so
 * baselining at V4 would mark it applied without ever running it and leave every adopted database
 * without those indexes. Bump this only once a migration has shipped and been applied everywhere.
 *
 * SchemaMigrationTest fails if a migration is shipped without bumping this.
 */
const val BASELINE_VERSION = "3"

/**
 * Every table V1 creates. Adoption is only safe when all of them are present: a schema carrying a
 * subset is not a family-sync database that lost its history table, and marking it at
 * [BASELINE_VERSION] would leave it permanently half-migrated with no error at all.
 */
private val FAMILY_TABLES = listOf(
    "families",
    "members",
    "invites",
    "member_tokens",
    "budget_periods",
    "transactions",
    "archived_transactions",
    "family_state",
    "period_limits",
    "saved_categories",
    "saved_tags",
    "recurring_templates",
    "savings_goals",
    "family_settings",
)

/**
 * Builds the pool, retrying the first connection.
 *
 * Supabase's pooler recycles backend connections and occasionally closes one while the PostgreSQL
 * startup packet is still being written. That surfaces as `SocketException: Broken pipe` under
 * `sendStartupPacket`, with nothing in the trace to suggest it was transient. Without a retry a
 * single such blip aborts the deploy; with one, boot rides through it. The password is never logged.
 */
fun createDataSource(jdbcUrl: String, user: String, password: String): DataSource {
    var lastFailure: Throwable? = null
    repeat(POOL_INIT_ATTEMPTS) { attempt ->
        try {
            return HikariDataSource(hikariConfig(jdbcUrl, user, password))
        } catch (failure: Exception) {
            lastFailure = failure
            val root = rootCause(failure)
            System.err.println(
                "database pool init attempt ${attempt + 1}/$POOL_INIT_ATTEMPTS failed " +
                    "connecting to ${jdbcUrl.redactedTarget()}: ${root.javaClass.simpleName}: ${root.message}"
            )
            if (attempt < POOL_INIT_ATTEMPTS - 1) Thread.sleep(POOL_INIT_BACKOFF_MS * (attempt + 1))
        }
    }
    throw IllegalStateException(
        "Could not connect to the database at ${jdbcUrl.redactedTarget()} after " +
            "$POOL_INIT_ATTEMPTS attempts. Check DATABASE_URL uses the Supabase session-mode pooler " +
            "(port 5432, username postgres.<project-ref>), that DATABASE_PASSWORD is the current one, " +
            "and that the project is not paused. Last error: ${rootCause(lastFailure!!).message}",
        lastFailure,
    )
}

private fun hikariConfig(jdbcUrl: String, user: String, password: String): HikariConfig {
    val hikari = HikariConfig()
    hikari.jdbcUrl = jdbcUrl
    hikari.username = user
    hikari.password = password
    hikari.maximumPoolSize = MAX_POOL_SIZE
    hikari.minimumIdle = 0
    hikari.connectionTimeout = CONNECTION_TIMEOUT_MS
    hikari.maxLifetime = MAX_LIFETIME_MS
    hikari.keepaliveTime = KEEPALIVE_MS
    // Retries inside Hikari's own pool construction, so a dropped startup packet is survivable
    // even before the loop above starts.
    hikari.initializationFailTimeout = POOL_INIT_TIMEOUT_MS
    return hikari
}

private fun rootCause(failure: Throwable): Throwable {
    var current = failure
    while (true) current = current.cause ?: return current
}

private fun String.redactedTarget(): String {
    val authority = substringAfter("//", "").substringBefore('/')
    return authority.ifBlank { "the configured host" }
}

fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(adoptable(dataSource))
        .baselineVersion(BASELINE_VERSION)
        .load()
        .migrate()
}

/**
 * True only for a schema that already carries every table the migrations create.
 *
 * Flyway's own `baselineOnMigrate` baselines any non-empty schema, so a database holding one unrelated
 * table would be adopted, marked at [BASELINE_VERSION], and left permanently missing the other thirteen
 * with nothing in the logs to say so. A schema with no tables at all is a fresh database, where
 * baselining is irrelevant because every migration runs anyway.
 */
private fun adoptable(dataSource: DataSource): Boolean = dataSource.connection.use { connection ->
    val present = mutableSetOf<String>()
    connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
        while (rows.next()) present.add(rows.getString("TABLE_NAME").lowercase())
    }
    FAMILY_TABLES.all { it in present }
}
