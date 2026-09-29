package family.sync.family

import family.sync.auth.Principal
import family.sync.auth.TokenService
import family.sync.db.setUuid
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CODE_LENGTH = 8
private val INVITE_LIFETIME = Duration.ofMinutes(15)

data class FamilyCredentials(
    val familyId: String,
    val memberId: String,
    val token: String,
)

data class MintedInvite(
    val code: String,
    val expiresAt: String,
)

class FamilyStore(
    private val dataSource: DataSource,
    private val tokenService: TokenService = TokenService(dataSource),
) {

    private val random = SecureRandom()

    fun createFamily(displayName: String): FamilyCredentials {
        val name = displayName.trim()
        if (name.isEmpty()) throw BadRequestException("display_name_required")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val familyId = insertReturningUuid(connection, "insert into families default values returning id")
                val memberId = insertReturningUuid(
                    connection,
                    "insert into members (family_id, display_name, is_owner) values (?, ?, true) returning id",
                    familyId,
                    name,
                )
                val token = tokenService.mint(connection, memberId, familyId)
                connection.commit()
                return FamilyCredentials(familyId, memberId, token)
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun mintInvite(principal: Principal): MintedInvite {
        if (!isOwner(principal.memberId)) throw ForbiddenException("owner_only")
        val code = generateCode()
        val expiresAt = Instant.now().plus(INVITE_LIFETIME)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into invites (code, family_id, created_by, expires_at) values (?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, code)
                statement.setUuid(2, principal.familyId)
                statement.setUuid(3, principal.memberId)
                statement.setTimestamp(4, Timestamp.from(expiresAt))
                statement.executeUpdate()
            }
        }
        return MintedInvite(code, expiresAt.toString())
    }

    fun redeemInvite(code: String, displayName: String): FamilyCredentials {
        val name = displayName.trim()
        if (name.isEmpty()) throw BadRequestException("display_name_required")
        val normalized = code.trim().uppercase()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val invite = readInvite(connection, normalized)
                    ?: throw NotFoundException("invite_not_found")
                val redeemedAt = invite.redeemedAt
                if (redeemedAt != null) throw ConflictException("invite_already_used")
                if (invite.expiresAt.isBefore(Instant.now())) throw GoneException("invite_expired")
                val memberId = insertReturningUuid(
                    connection,
                    "insert into members (family_id, display_name, is_owner) values (?, ?, false) returning id",
                    invite.familyId,
                    name,
                )
                connection.prepareStatement(
                    "update invites set redeemed_at = ? where code = ? and redeemed_at is null"
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(Instant.now()))
                    statement.setString(2, normalized)
                    if (statement.executeUpdate() != 1) throw ConflictException("invite_already_used")
                }
                val token = tokenService.mint(connection, memberId, invite.familyId)
                connection.commit()
                return FamilyCredentials(invite.familyId, memberId, token)
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun isOwner(memberId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("select is_owner from members where id = ?").use { statement ->
            statement.setUuid(1, memberId)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean("is_owner") }
        }
    }

    fun displayNameOf(memberId: String): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("select display_name from members where id = ?").use { statement ->
            statement.setUuid(1, memberId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString("display_name") else null
            }
        }
    }

    fun familyDisplayNames(familyId: String): List<String> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "select display_name from members where family_id = ? order by joined_at, id"
        ).use { statement ->
            statement.setUuid(1, familyId)
            statement.executeQuery().use { rows ->
                val names = mutableListOf<String>()
                while (rows.next()) names.add(rows.getString("display_name"))
                names
            }
        }
    }

    private fun readInvite(connection: Connection, code: String): Invite? =
        connection.prepareStatement(
            "select family_id, expires_at, redeemed_at from invites where code = ?"
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                Invite(
                    familyId = rows.getString("family_id"),
                    expiresAt = rows.getTimestamp("expires_at").toInstant(),
                    redeemedAt = rows.getTimestamp("redeemed_at")?.toInstant(),
                )
            }
        }

    private fun insertReturningUuid(
        connection: Connection,
        sql: String,
        vararg parameters: String,
    ): String {
        var inserted: String? = null
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setUuid(index + 1, value) }
            statement.executeQuery().use { rows -> if (rows.next()) inserted = rows.getString(1) }
        }
        return inserted ?: error("insert returned no id")
    }

    private fun generateCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
    }

    private data class Invite(
        val familyId: String,
        val expiresAt: Instant,
        val redeemedAt: Instant?,
    )
}
