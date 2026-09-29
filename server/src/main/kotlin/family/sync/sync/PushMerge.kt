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

sealed interface MergeDecision {
    data class Accept(val version: Int) : MergeDecision
    data class Reject(val winnerMemberId: String?) : MergeDecision
}

fun decidePush(stored: StoredRecord?, incoming: PushChange): MergeDecision {
    if (stored == null) return MergeDecision.Accept(incoming.version + 1)
    if (incoming.version >= stored.version) return MergeDecision.Accept(incoming.version + 1)
    if (incoming.deletedAt != null && stored.deletedAt == null) return MergeDecision.Accept(incoming.version + 1)
    if (stored.deletedAt != null && incoming.deletedAt == null) return MergeDecision.Reject(stored.memberId)
    if (incoming.updatedAt > stored.updatedAt) return MergeDecision.Accept(incoming.version + 1)
    return MergeDecision.Reject(stored.memberId)
}
