package family.sync.auth

import family.sync.db.setUuid
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import javax.sql.DataSource

class TokenService(private val dataSource: DataSource) {

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
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select member_id, family_id from member_tokens where token_hash = ?"
            ).use { statement ->
                statement.setString(1, hashToken(token))
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
