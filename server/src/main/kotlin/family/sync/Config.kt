package family.sync

import java.net.URI

data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val port: Int = 8080,
)

fun loadConfig(env: Map<String, String>): Config = Config(
    databaseUrl = toJdbcUrl(
        raw = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
        sslMode = env["DATABASE_SSL_MODE"] ?: "require",
    ),
    databaseUser = env["DATABASE_USER"] ?: "postgres",
    databasePassword = env["DATABASE_PASSWORD"] ?: "",
    port = env["PORT"]?.toInt() ?: 8080,
)

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
