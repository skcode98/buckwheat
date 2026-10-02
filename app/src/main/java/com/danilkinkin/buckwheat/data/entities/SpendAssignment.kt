package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

enum class SpendAssignmentStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    ;

    val isResolved: Boolean get() = this != PENDING

    companion object {
        fun fromStored(value: String?): SpendAssignmentStatus =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}

/**
 * A spend the head of the family has recorded against another member's budget, awaiting that member's
 * answer.
 *
 * The assignment is a separate object from the spend it becomes, rather than a pending `Transaction`,
 * for two reasons. The target has to be able to say no, and a row that might or might not count
 * against their remaining budget is a much worse thing to show someone than a clearly separate
 * request. And accepting has to be the target's own act: if acceptance merely deleted a request, the
 * head could achieve the same effect by writing the row directly, and the consent would be theatre.
 *
 * On acceptance a real `Transaction` is materialised, tagged with [SpendAssignment.assignmentId], so
 * the resulting row is an ordinary spend the member owns and can later edit or delete. The assignment
 * row stays behind as the audit trail of who asked.
 *
 * Deliberately has no foreign key to `budget_periods`. A request that outlives the period it was
 * made in must still restore cleanly, and a dangling period reference would make backup restore fail.
 */
@Entity(
    tableName = "spend_assignments",
    indices = [Index("family_id"), Index("period_id"), Index("target_member_id")],
)
data class SpendAssignment(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "period_id")
    val periodId: String,

    @ColumnInfo(name = "target_member_id")
    val targetMemberId: String,

    @ColumnInfo(name = "created_by_member_id")
    val createdByMemberId: String,

    @ColumnInfo(name = "amount")
    val amount: BigDecimal,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "status", defaultValue = "PENDING")
    val status: String = SpendAssignmentStatus.PENDING.name,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Date? = null,

    @ColumnInfo(name = "family_id")
    val familyId: String? = null,

    @ColumnInfo(name = "sync_seq", defaultValue = "0")
    val syncSeq: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "version", defaultValue = "1")
    val version: Int = 1,
) {
    val assignmentStatus: SpendAssignmentStatus get() = SpendAssignmentStatus.fromStored(status)
}

/** Why a resolution was refused, or null when it is allowed. */
enum class ResolutionProblem {
    ALREADY_RESOLVED,
    NOT_THE_TARGET,
    CREATOR_CANNOT_RESOLVE,
    AMOUNT_NOT_POSITIVE,
}
