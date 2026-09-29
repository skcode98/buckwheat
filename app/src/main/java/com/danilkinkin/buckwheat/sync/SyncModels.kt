package com.danilkinkin.buckwheat.sync

data class WireRecord(
    val table: String,
    val id: String,
    val seq: Long,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val memberId: String?,
)

data class LocalRecord(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val dirty: Boolean,
    val memberId: String?,
    val familyId: String? = null,
    val syncSeq: Long = 0L,
)

data class ConflictNotice(
    val table: String,
    val id: String,
    val wonByMemberId: String,
)

data class MergeResult(
    val records: List<LocalRecord>,
    val cursor: Long,
    val conflicts: List<ConflictNotice>,
)

data class SyncRequest(
    val cursor: Long,
    val changes: List<LocalRecord>,
)

data class SyncResponse(
    val cursor: Long,
    val accepted: List<String>,
    val records: List<WireRecord>,
    val conflicts: List<ConflictNotice>,
)

data class SyncApply(
    val records: List<LocalRecord>,
    val cursor: Long,
    val conflicts: List<ConflictNotice>,
)

sealed interface SyncOutcome {
    data object NotEnrolled : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
    data class Synced(val cursor: Long, val conflicts: List<ConflictNotice>) : SyncOutcome
}
