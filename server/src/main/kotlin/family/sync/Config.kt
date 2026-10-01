package family.sync

import java.net.URI

data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val databaseHost: String = "",
    val databasePort: Int = 5432,
    val port: Int = 8080,
)

/** Supabase's transaction-mode pooler. Flyway needs real sessions, so this port can never work here. */
private const val TRANSACTION_POOLER_PORT = 6543

fun loadConfig(env: Map<String, String>): Config {
    val raw = env["DATABASE_URL"] ?: error("DATABASE_URL is required")
    val jdbcUrl = toJdbcUrl(raw, env["DATABASE_SSL_MODE"] ?: "require")
    val host = jdbcUrl.hostFromJdbcUrl()
    val databasePort = jdbcUrl.portFromJdbcUrl()
    // Supavisor's transaction-mode pooler accepts the TCP connection and then drops it while the
    // startup packet is still being written, which surfaces as an opaque `Broken pipe` inside
    // `sendStartupPacket` with no mention of the port anywhere in the stack trace. Naming it here
    // turns a baffling crash into a one-line fix.
    if (databasePort == TRANSACTION_POOLER_PORT) {
        error(
            "DATABASE_URL points at port $TRANSACTION_POOLER_PORT, Supabase's transaction-mode pooler. " +
                "It cannot be used here: migrations and Hikari both need a real session. Use the " +
                "session-mode pooler on port 5432 " +
                "(aws-0-<region>.pooler.supabase.com) and keep the `postgres.<project-ref>` username."
        )
    }
    return Config(
        databaseUrl = jdbcUrl,
        databaseUser = env["DATABASE_USER"]?.takeIf { it.isNotBlank() }
            ?: userFromConnectionString(raw)
            ?: "postgres",
        databasePassword = env["DATABASE_PASSWORD"]?.takeIf { it.isNotBlank() }
            ?: error("DATABASE_PASSWORD is required and must not be blank"),
        databaseHost = host,
        databasePort = databasePort,
        port = env["PORT"]?.toInt() ?: 8080,
    )
}

private fun String.hostFromJdbcUrl(): String =
    substringAfter("//", "").substringBefore('/').substringBefore(':')

private fun String.portFromJdbcUrl(): Int {
    val authority = substringAfter("//", "").substringBefore('/')
    val port = authority.substringAfter(':', "")
    return port.toIntOrNull() ?: 5432
}

/** Never includes the password, so this is safe to write to logs. */
fun Config.redactedTarget(): String = "$databaseHost:$databasePort as $databaseUser"

/**
 * `toJdbcUrl` deliberately strips `user:password@` so Hikari supplies the credentials instead of
 * leaking them into a logged connection url. That also threw the username away, which broke
 * Supabase's shared pooler: Supavisor resolves the tenant from the username, so a pasted
 * `postgresql://postgres.<project-ref>:pw@...pooler.supabase.com/...` fell back to a bare
 * `postgres` and failed with `ENOTFOUND: tenant/user postgres. not found`.
 */
private fun userFromConnectionString(raw: String): String? {
    if (raw.startsWith("jdbc:")) return null
    val userInfo = runCatching { URI(raw).userInfo }.getOrNull() ?: return null
    return userInfo.substringBefore(':').takeIf { it.isNotBlank() }
}

private fun toJdbcUrl(raw: String, sslMode: String): String {
    if (raw.startsWith("jdbc:")) return withSslMode(raw, sslMode)
    val uri = URI(raw)
    when (uri.scheme?.lowercase()) {
        "postgresql", "postgres" -> Unit
        else -> error("DATABASE_URL must be a postgres uri or jdbc url, got: $raw")
    }
    val host = requireNotNull(uri.host) { "DATABASE_URL has no host: $raw" }
    val authority = if (uri.port > 0) "$host:${uri.port}" else host
    val query = buildList {
        uri.query
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.filterNot { it.substringBefore('=').equals("sslmode", ignoreCase = true) }
            ?.forEach { add(it) }
        add("sslmode=$sslMode")
    }.joinToString("&")
    return "jdbc:postgresql://$authority${uri.path}?$query"
}

private fun withSslMode(jdbcUrl: String, sslMode: String): String = when {
    jdbcUrl.contains("sslmode=") -> jdbcUrl
    jdbcUrl.contains('?') -> "$jdbcUrl&sslmode=$sslMode"
    else -> "$jdbcUrl?sslmode=$sslMode"
}
