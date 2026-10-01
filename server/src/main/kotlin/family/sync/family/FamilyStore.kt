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
private const val REDEEMED_INVITE_RETENTION_DAYS = 1
private const val EXPIRED_INVITE_RETENTION_DAYS = 7
private val INVITE_LIFETIME = Duration.ofMinutes(15)

const val DEFAULT_MAX_OUTSTANDING_INVITES: Int = 20

data class FamilyCredentials(
    val familyId: String,
    val memberId: String,
    val token: String,
)

data class MintedInvite(
    val code: String,
    val expiresAt: String,
)

/**
 * One member as the family sees them.
 *
 * The id is not decoration. Every synced transaction carries the `member_id` of whoever logged it,
 * so without the id a client holding a spend cannot say which member it belongs to, and a conflict
 * naming `wonByMemberId` cannot be resolved to a human either. Returning names alone made the roster
 * displayable but left every attribution question unanswerable.
 */
data class FamilyMember(
    val id: String,
    val displayName: String,
    val isOwner: Boolean,
    val joinedAt: String,
)

class FamilyStore(
    private val dataSource: DataSource,
    private val tokenService: TokenService = TokenService(dataSource),
    private val maxOutstandingInvites: Int = DEFAULT_MAX_OUTSTANDING_INVITES,
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
            connection.autoCommit = false
            try {
                pruneInvites(connection)
                if (countOutstandingInvites(connection, principal.familyId) >= maxOutstandingInvites) {
                    throw ConflictException("too_many_invites")
                }
                connection.prepareStatement(
                    "insert into invites (code, family_id, created_by, expires_at) values (?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, code)
                    statement.setUuid(2, principal.familyId)
                    statement.setUuid(3, principal.memberId)
                    statement.setTimestamp(4, Timestamp.from(expiresAt))
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
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
                val familyId = claimInvite(connection, normalized)
                val memberId = insertReturningUuid(
                    connection,
                    "insert into members (family_id, display_name, is_owner) values (?, ?, false) returning id",
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

    fun leave(principal: Principal) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                // The member row and every token that authenticates as it go in one transaction.
                // Revoking the caller's token separately means a crash in between leaves a live
                // token for a member that no longer exists, which is exactly the state leave exists
                // to remove.
                connection.prepareStatement("delete from member_tokens where member_id = ?").use { statement ->
                    statement.setUuid(1, principal.memberId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("delete from members where id = ?").use { statement ->
                    statement.setUuid(1, principal.memberId)
                    statement.executeUpdate()
                }
                connection.commit()
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

    fun familyMembers(familyId: String): List<FamilyMember> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "select id, display_name, is_owner, joined_at from members where family_id = ? order by joined_at, id"
        ).use { statement ->
            statement.setUuid(1, familyId)
            statement.executeQuery().use { rows ->
                val members = mutableListOf<FamilyMember>()
                while (rows.next()) {
                    members.add(
                        FamilyMember(
                            id = rows.getString("id"),
                            displayName = rows.getString("display_name"),
                            isOwner = rows.getBoolean("is_owner"),
                            joinedAt = rows.getTimestamp("joined_at").toInstant().toString(),
                        )
                    )
                }
                members
            }
        }
    }

    private fun claimInvite(connection: Connection, code: String): String {
        val claimed = connection.prepareStatement(
            "update invites set redeemed_at = now() where code = ? and redeemed_at is null " +
                "and expires_at > now() returning family_id"
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        return claimed ?: throw unusableInvite(connection, code)
    }

    private fun unusableInvite(connection: Connection, code: String): ApiException {
        val invite = readInvite(connection, code) ?: throw NotFoundException("invite_not_found")
        return if (invite.redeemedAt != null) {
            ConflictException("invite_already_used")
        } else if (!invite.expiresAt.isAfter(Instant.now())) {
            GoneException("invite_expired")
        } else {
            ConflictException("invite_already_used")
        }
    }

    private fun pruneInvites(connection: Connection) {
        connection.prepareStatement(
            "delete from invites where " +
                "(redeemed_at is not null and redeemed_at < now() - make_interval(days => ?)) or " +
                "(expires_at < now() - make_interval(days => ?))"
        ).use { statement ->
            statement.setInt(1, REDEEMED_INVITE_RETENTION_DAYS)
            statement.setInt(2, EXPIRED_INVITE_RETENTION_DAYS)
            statement.executeUpdate()
        }
    }

    private fun countOutstandingInvites(connection: Connection, familyId: String): Int =
        connection.prepareStatement(
            "select count(*) from invites where family_id = ? and redeemed_at is null and expires_at > now()"
        ).use { statement ->
            statement.setUuid(1, familyId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    private fun readInvite(connection: Connection, code: String): Invite? =
        connection.prepareStatement(
            "select expires_at, redeemed_at from invites where code = ?"
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                Invite(
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
        val expiresAt: Instant,
        val redeemedAt: Instant?,
    )
}
