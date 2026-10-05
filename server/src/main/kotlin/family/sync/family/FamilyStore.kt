package family.sync.family

import family.sync.auth.Principal
import family.sync.auth.TokenService
import family.sync.db.setUuid
import java.security.SecureRandom
import java.sql.Connection
import javax.sql.DataSource

private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CODE_LENGTH = 8
private val JOIN_CODE_LIFETIME_DAYS = 30

data class FamilyCredentials(
    val familyId: String,
    val memberId: String,
    val token: String,
    val joinCode: String,
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
    val departed: Boolean,
    val joinedAt: String,
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
                // The join code is minted by create rather than on request, so enrolment is one round
                // trip and the code a member shares is the same one its own devices redeem. `invites.code`
                // is text, not uuid, so the code is bound as a string.
                val code = generateCode()
                connection.prepareStatement(
                    "insert into invites (code, family_id, created_by, expires_at) " +
                        "values (?, ?, ?, now() + make_interval(days => ?))"
                ).use { statement ->
                    statement.setString(1, code)
                    statement.setUuid(2, familyId)
                    statement.setUuid(3, memberId)
                    statement.setInt(4, JOIN_CODE_LIFETIME_DAYS)
                    statement.executeUpdate()
                }
                val token = tokenService.mint(connection, memberId, familyId)
                connection.commit()
                return FamilyCredentials(familyId, memberId, token, code)
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun redeemInvite(code: String, displayName: String): FamilyCredentials {
        val name = displayName.trim()
        if (name.isEmpty()) throw BadRequestException("display_name_required")
        val normalized = code.trim().uppercase()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val familyId = joinableFamilyId(connection, normalized)
                val memberId = insertReturningUuid(
                    connection,
                    "insert into members (family_id, display_name, is_owner) values (?, ?, false) returning id",
                    familyId,
                    name,
                )
                val token = tokenService.mint(connection, memberId, familyId)
                connection.commit()
                return FamilyCredentials(familyId, memberId, token, normalized)
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    /**
     * Closes the membership without erasing the member.
     *
     * The row is kept because every transaction the member logged points at it, and `member_id` is
     * `on delete set null`: deleting the row would silently strip the author from that history, and
     * `authorize` reads a null author as a refusal, freezing the rows with nobody left to reconcile
     * against. Stamping `departed_at` closes the membership -- every token that authenticates as the
     * member is deleted, so the departed device is out -- while leaving the attribution intact.
     *
     * The stamp and the token deletion share one transaction. Revoking separately means a crash in
     * between leaves a live token for a member that is already departed, which is exactly the state
     * this exists to prevent.
     */
    fun leave(principal: Principal) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "update members set departed_at = now() where id = ? and family_id = ?"
                ).use { statement ->
                    statement.setUuid(1, principal.memberId)
                    statement.setUuid(2, principal.familyId)
                    statement.executeUpdate()
                }
                connection.prepareStatement("delete from member_tokens where member_id = ?").use { statement ->
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
            "select id::text, display_name, departed_at is not null, joined_at " +
                "from members where family_id = ? order by joined_at, id"
        ).use { statement ->
            statement.setUuid(1, familyId)
            statement.executeQuery().use { rows ->
                val members = mutableListOf<FamilyMember>()
                while (rows.next()) {
                    members.add(
                        FamilyMember(
                            id = rows.getString(1),
                            displayName = rows.getString(2),
                            departed = rows.getBoolean(3),
                            joinedAt = rows.getTimestamp(4).toInstant().toString(),
                        )
                    )
                }
                members
            }
        }
    }

    private fun joinableFamilyId(connection: Connection, code: String): String {
        val found = connection.prepareStatement(
            "select family_id::text, expires_at > now() from invites where code = ?"
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getBoolean(2) else null
            }
        } ?: throw NotFoundException("invite_not_found")
        if (!found.second) throw GoneException("invite_expired")
        return found.first
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
}
