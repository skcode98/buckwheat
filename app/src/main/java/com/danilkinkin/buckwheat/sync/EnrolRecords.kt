package com.danilkinkin.buckwheat.sync

fun enrolRecords(
    records: List<LocalRecord>,
    memberId: String,
    familyId: String,
    enrolledAt: Long,
): List<LocalRecord> = records.map { record ->
    record.copy(
        memberId = memberId,
        familyId = familyId,
        updatedAt = enrolledAt,
        version = 1,
        deletedAt = null,
        dirty = false,
        syncSeq = 0L,
    )
}
