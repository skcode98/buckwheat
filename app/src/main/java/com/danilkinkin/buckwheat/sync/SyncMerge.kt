package com.danilkinkin.buckwheat.sync

private data class Outcome(val record: LocalRecord, val winner: String?)

private fun resolve(local: LocalRecord, remote: WireRecord): Outcome {
    if (local.dirty && remote.deletedAt == null) {
        val winner = local.memberId.takeIf { local.version != remote.version }
        return Outcome(local, winner)
    }
    val remoteWins = remote.deletedAt != null || remote.updatedAt > local.updatedAt
    if (!remoteWins) {
        val winner = local.memberId.takeIf { local.version != remote.version }
        return Outcome(local, winner)
    }
    val winner = remote.memberId.takeIf { local.version != remote.version }
    return Outcome(
        LocalRecord(
            table = remote.table,
            id = remote.id,
            updatedAt = remote.updatedAt,
            version = remote.version,
            deletedAt = remote.deletedAt,
            payload = remote.payload,
            dirty = false,
            memberId = remote.memberId,
        ),
        winner,
    )
}

fun mergePull(
    local: List<LocalRecord>,
    remote: List<WireRecord>,
    cursor: Long,
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
        val outcome = resolve(record, incoming)
        records.add(outcome.record)
        val winner = outcome.winner
        if (winner != null) {
            conflicts.add(ConflictNotice(record.table, record.id, winner))
        }
    }

    for (incoming in unmatched.values) {
        records.add(
            LocalRecord(
                table = incoming.table,
                id = incoming.id,
                updatedAt = incoming.updatedAt,
                version = incoming.version,
                deletedAt = incoming.deletedAt,
                payload = incoming.payload,
                dirty = false,
                memberId = incoming.memberId,
            )
        )
    }

    val highestSeq = remote.maxOfOrNull { it.seq } ?: cursor
    return MergeResult(records, maxOf(cursor, highestSeq), conflicts)
}
