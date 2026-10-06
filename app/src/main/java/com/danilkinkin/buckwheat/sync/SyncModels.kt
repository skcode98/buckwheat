package com.danilkinkin.buckwheat.sync

/**
 * Identifies one record in a sync batch. A bare id is not enough: the same id can legally live in
 * two tables, so `accepted` and `conflicts` entries are keyed by table AND id on the wire.
 *
 * Wire shape, agreed with the server owner and used verbatim on both sides:
 * ```
 * {
 *   "cursor": 12345,
 *   "hasMore": true,
 *   "accepted":  [ {"table":"transactions","id":"<uuid>"} ],
 *   "records":   [ {table,id,seq,updatedAt,version,deletedAt?,memberId?,payload} ],
 *   "conflicts": [ {"table":"transactions","id":"<uuid>","reason":"stale_version","wonByMemberId":"<uuid>"} ]
 * }
 * ```
 * `wonByMemberId` is omitted, not null, when unknown, matching how `deletedAt` and `memberId` are
 * omitted from `records`.
 */
data class RecordKey(
    val table: String,
    val id: String,
)

data class WireRecord(
    val table: String,
    val id: String,
    val seq: Long,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val memberId: String?,
) {
    val key: RecordKey get() = RecordKey(table, id)
}

data class LocalRecord(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val dirty: Boolean = false,
    val memberId: String? = null,
    val familyId: String? = null,
    val syncSeq: Long = 0L,
) {
    val key: RecordKey get() = RecordKey(table, id)
}

/** Why the server refused a pushed change. An unknown or absent wire value degrades to [STALE_VERSION]. */
enum class ConflictReason(val wire: String) {
    STALE_VERSION("stale_version"),
    DELETED_REMOTELY("deleted_remotely"),
    CROSS_FAMILY_WRITE("cross_family_write"),
    ;

    companion object {
        fun fromWire(value: String?): ConflictReason =
            entries.firstOrNull { it.wire == value } ?: STALE_VERSION
    }
}

/**
 * A record two devices wrote differently, or a pushed change the server refused.
 *
 * [wonByMemberId] is null when no member won it. That is always the case for the memberless tables
 * (budget periods, categories, tags, recurring templates, goals), whose rows are shared by the family
 * rather than owned by a person, and it is the correct reading of an omitted wire field. A null winner
 * is a valid notice and must survive a round trip, never be dropped as malformed.
 */
data class ConflictNotice(
    val table: String,
    val id: String,
    val wonByMemberId: String?,
    val reason: ConflictReason = ConflictReason.STALE_VERSION,
) {
    val key: RecordKey get() = RecordKey(table, id)
}

data class MergeResult(
    val records: List<LocalRecord>,
    val cursor: Long,
    val conflicts: List<ConflictNotice>,
)

data class SyncRequest(
    val cursor: Long,
    val changes: List<LocalRecord>,
    val since: Long? = null,
)

data class SyncResponse(
    val cursor: Long,
    val accepted: List<RecordKey>,
    val records: List<WireRecord>,
    val conflicts: List<ConflictNotice>,
    /** The server capped the pull window, so the caller has to page again from [cursor]. */
    val hasMore: Boolean = false,
)

/**
 * One push this run got a decision on, tagged with the version that was sent.
 *
 * The version is what makes dequeueing safe. `pending_mutations` is keyed by (table, id) only and
 * `SyncDirtyMarker` re-queues an edit onto that same key, so a bare key cannot tell "this exact
 * change was answered" from "this key was edited again while the request was in flight". Carrying
 * the pushed version lets the apply compare it against the row's current version and keep the queue
 * entry for anything that moved on.
 */
data class SettledChange(
    val key: RecordKey,
    val version: Int,
)

data class SyncApply(
    val records: List<LocalRecord>,
    val cursor: Long,
    val conflicts: List<ConflictNotice>,
    val settled: List<SettledChange> = emptyList(),
)

sealed interface SyncOutcome {
    data object NotEnrolled : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
    data class Synced(val cursor: Long, val conflicts: List<ConflictNotice>) : SyncOutcome
}