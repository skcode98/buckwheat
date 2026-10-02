package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.*

enum class TransactionType {
    SET_DAILY_BUDGET,
    INCOME,
    SPENT
}

/**
 * Whose money a spend came out of.
 *
 * [HOUSEHOLD] exists because "no member" was already overloaded: a row created before enrolment has
 * a null `memberId` and means "not yet attributable", which is a different statement from "belongs
 * to everybody". Rent and insurance need the second statement, and overloading the first would have
 * made a shared tablet's unattributed rows indistinguishable from the household's rent.
 *
 * A HOUSEHOLD row always has a null `member_id`. Enforced rather than assumed so that a member
 * remaining budget can never be reduced by someone else's expense without the split being visible.
 */
enum class SpendBucket {
    MEMBER,
    HOUSEHOLD,
    ;

    companion object {
        fun fromStored(value: String?): SpendBucket =
            entries.firstOrNull { it.name == value } ?: MEMBER
    }
}

@Entity(
    tableName = "transactions",
    indices = [Index("type", "date"), Index("family_id")]
)
data class Transaction(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = newSyncId(),

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "value")
    val value: BigDecimal,

    @ColumnInfo(name = "date")
    val date: Date,

    @ColumnInfo(name = "comment", defaultValue = "")
    val comment: String = "",

    // Predefined spend category (SpendCategory.name) assigned by the AI classifier and shown
    // in analytics only. Null until the classifier runs; display falls back to offline
    // keyword matching (SpendCategorizer.categoryFor).
    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "member_id")
    val memberId: String? = null,

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

    @ColumnInfo(name = "bucket", defaultValue = "MEMBER")
    val bucket: String = SpendBucket.MEMBER.name,

    /**
     * The assignment this row came from, if any.
     *
     * Doubles as the row's own id, because a spend materialised from an assignment has to be
     * convergent: two devices pulling the same accepted assignment must end up with one row, not two.
     * Recorded on the row rather than looked up through the assignment so the provenance survives
     * after the assignment is archived away.
     */
    @ColumnInfo(name = "assignment_id")
    val assignmentId: String? = null,

    @ColumnInfo(name = "assigned_by_member_id")
    val assignedByMemberId: String? = null,
)

/**
 * Fills in the member a spend belongs to, and only ever fills it in.
 *
 * Two callers need opposite precedence, and a single "resolve" function cannot serve both:
 * `SpendsViewModel.addSpent` supplies the session member as a default for a row that was just
 * built, while an undo replays a row that already carries whoever was chosen the first time and must
 * not have that silently rewritten to whoever is signed in now. So an existing member wins, and the
 * caller that means to change it sets the field on the row it constructs instead of passing an
 * override here.
 *
 * A null member is a no-op rather than an un-attribution, because rows created before enrolment have
 * no member and stay that way until `RoomSyncDatabase.enrolAll` gives them one. That is the only path
 * allowed to fill them, and it writes the column directly.
 */
fun Transaction.attributedTo(memberId: String?): Transaction =
    if (memberId == null || this.memberId != null) this else copy(memberId = memberId)

/**
 * Re-buckets a spend as household money.
 *
 * Clearing [memberId] here rather than trusting the caller is the point: a household row with a
 * member attached would be counted twice, once against the pool and once against a person, and the
 * person it hit would be whichever the caller happened to pass.
 */
fun Transaction.asHouseholdSpend(): Transaction =
    if (bucket == SpendBucket.HOUSEHOLD.name && memberId == null) this
    else copy(bucket = SpendBucket.HOUSEHOLD.name, memberId = null)
