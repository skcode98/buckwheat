package family.sync.sync

data class PushChange(
    val table: String,
    val id: String,
    val version: Int,
    val updatedAt: Long,
    val deletedAt: Long?,
    val payload: String,
)

data class StoredRecord(
    val id: String,
    val version: Int,
    val updatedAt: Long,
    val deletedAt: Long?,
    val memberId: String?,
)

/** Why one change in a batch did not land. The wire value tells the client how to explain itself. */
enum class RejectReason(val wire: String) {
    STALE_VERSION("stale_version"),
    DELETED_REMOTELY("deleted_remotely"),
    CROSS_FAMILY_WRITE("cross_family_write"),
    CROSS_MEMBER_WRITE("cross_member_write"),
}

sealed interface MergeDecision {
    data class Accept(val version: Int) : MergeDecision
    data class Reject(val reason: RejectReason, val winnerMemberId: String?) : MergeDecision
}

private const val MAX_VERSION = Int.MAX_VALUE - 1

/**
 * The version a new write is given: one past the higher of what is stored and what was pushed.
 *
 * Saturating rather than overflowing matters. `Int.MAX_VALUE + 1` wraps to a negative version, which
 * every later comparison would read as older than everything stored, so the record could never be
 * written again. At the ceiling no newer version exists to express, and holding the maximum is the
 * only reading that stays monotonic.
 */
private fun nextVersion(stored: StoredRecord?, incoming: PushChange): Int {
    val highest = maxOf(stored?.version ?: 0, incoming.version)
    return if (highest >= MAX_VERSION) Int.MAX_VALUE else highest + 1
}

fun decidePush(stored: StoredRecord?, incoming: PushChange): MergeDecision {
    val next = nextVersion(stored, incoming)
    if (stored == null) return MergeDecision.Accept(next)
    if (incoming.version >= stored.version) return MergeDecision.Accept(next)
    if (incoming.deletedAt != null && stored.deletedAt == null) return MergeDecision.Accept(next)
    if (stored.deletedAt != null && incoming.deletedAt == null) {
        return MergeDecision.Reject(RejectReason.DELETED_REMOTELY, stored.memberId)
    }
    if (incoming.updatedAt > stored.updatedAt) return MergeDecision.Accept(next)
    return MergeDecision.Reject(RejectReason.STALE_VERSION, stored.memberId)
}