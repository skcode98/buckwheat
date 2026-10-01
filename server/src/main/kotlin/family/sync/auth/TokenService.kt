package family.sync.auth

import family.sync.db.setUuid
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.util.Base64
import javax.sql.DataSource

/**
 * No expiry by default.
 *
 * The lifetime is a window on `created_at`, so it is applied retroactively: shortening it does not
 * only affect future tokens, it invalidates every token already minted inside the new window. A
 * finite default would therefore log every enrolled device out the moment it was deployed, with no
 * re-enrol path on the client. Families revoke access explicitly through `leave`, and the token is
 * rotated by enrolling again, so expiry is opt-in via TOKEN_LIFETIME_DAYS rather than the default.
 */
private val DEFAULT_TOKEN_LIFETIME: Duration = Duration.ofDays(3650)
private val MAX_TOKEN_LIFETIME_MILLIS: Long = Duration.ofDays(3650).toMillis()
private const val MILLIS_PER_DAY = 86_400_000.0

class TokenService(
    private val dataSource: DataSource,
    private val tokenLifetime: Duration = tokenLifetimeFromEnv(),
) {

    private val random = SecureRandom()

    fun mint(memberId: String, familyId: String): String {
        val token = generate()
        dataSource.connection.use { connection ->
            persist(connection, token, memberId, familyId)
        }
        return token
    }

    fun mint(connection: Connection, memberId: String, familyId: String): String {
        val token = generate()
        persist(connection, token, memberId, familyId)
        return token
    }

    private fun persist(
        connection: Connection,
        token: String,
        memberId: String,
        familyId: String,
    ) {
        connection.prepareStatement(
            "insert into member_tokens (token_hash, member_id, family_id) values (?, ?, ?)"
        ).use { statement ->
            statement.setString(1, hashToken(token))
            statement.setUuid(2, memberId)
            statement.setUuid(3, familyId)
            statement.executeUpdate()
        }
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun verify(token: String): Principal? {
        if (token.isBlank()) return null
        val lifetimeMillis = tokenLifetime.toMillis().coerceAtLeast(0L).toDouble()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select member_id, family_id from member_tokens where token_hash = ? " +
                    "and created_at > now() - (interval '1 millisecond' * cast(? as double precision))"
            ).use { statement ->
                statement.setString(1, hashToken(token))
                statement.setDouble(2, lifetimeMillis)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    Principal(rows.getString("member_id"), rows.getString("family_id"))
                }
            }
        }
    }

    fun revoke(token: String) {
        if (token.isBlank()) return
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "delete from member_tokens where token_hash = ?"
            ).use { statement ->
                statement.setString(1, hashToken(token))
                statement.executeUpdate()
            }
        }
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

internal fun tokenLifetimeFromEnv(env: Map<String, String> = System.getenv()): Duration {
    val days = env["TOKEN_LIFETIME_DAYS"]?.trim()?.toDoubleOrNull() ?: return DEFAULT_TOKEN_LIFETIME
    val millis = days * MILLIS_PER_DAY
    if (!millis.isFinite() || millis <= 0.0) return DEFAULT_TOKEN_LIFETIME
    return Duration.ofMillis(millis.toLong().coerceIn(1L, MAX_TOKEN_LIFETIME_MILLIS))
}
