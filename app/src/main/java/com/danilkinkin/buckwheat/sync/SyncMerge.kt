package com.danilkinkin.buckwheat.sync

private data class Outcome(val record: LocalRecord, val conflict: ConflictNotice?)

internal fun WireRecord.toLocalRecord(
    familyId: String?,
    dirty: Boolean = false,
    syncSeq: Long = this.seq,
): LocalRecord = LocalRecord(
    table = table,
    id = id,
    updatedAt = updatedAt,
    version = version,
    deletedAt = deletedAt,
    payload = payload,
    dirty = dirty,
    memberId = memberId,
    familyId = familyId,
    syncSeq = syncSeq,
)

private fun conflict(
    table: String,
    id: String,
    versionsDiffer: Boolean,
    winnerMemberId: String?,
): ConflictNotice? = if (versionsDiffer) {
    ConflictNotice(table, id, winnerMemberId.orEmpty())
} else {
    null
}

private fun resolve(local: LocalRecord, remote: WireRecord, familyId: String?): Outcome {
    val versionsDiffer = local.version != remote.version
    if (local.dirty && remote.deletedAt == null) {
        return Outcome(
            local,
            conflict(local.table, local.id, versionsDiffer, local.memberId),
        )
    }
    val remoteWins = remote.deletedAt != null || remote.updatedAt > local.updatedAt
    if (!remoteWins) {
        return Outcome(
            local,
            conflict(local.table, local.id, versionsDiffer, local.memberId),
        )
    }
    return Outcome(
        remote.toLocalRecord(familyId = local.familyId ?: familyId),
        conflict(local.table, local.id, versionsDiffer, remote.memberId),
    )
}

fun mergePull(
    local: List<LocalRecord>,
    remote: List<WireRecord>,
    cursor: Long,
    familyId: String? = null,
): MergeResult {
    val unmatched = remote.associateBy { it.table to it.id }.toMutableMap()
    val records = mutableListOf<LocalRecord>()
    val conflicts = mutableListOf<ConflictNotice>()

    for (record in local) {
        val incoming = unmatched.remove(record.table to record.id)
        if (incoming == null) {
            records.add(record)
            continue
        }
        val outcome = resolve(record, incoming, familyId)
        records.add(outcome.record)
        outcome.conflict?.let { conflicts.add(it) }
    }

    for (incoming in unmatched.values) {
        records.add(incoming.toLocalRecord(familyId = familyId))
    }

    val highestSeq = remote.maxOfOrNull { it.seq } ?: cursor
    return MergeResult(records, maxOf(cursor, highestSeq), conflicts)
}
