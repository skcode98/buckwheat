package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * How a household expense is divided when a member's remaining budget is worked out.
 *
 * Named rather than a bare boolean because the two rules disagree in a way a member will notice:
 * under [EQUAL] a member who allocated nothing still carries a share of the rent, and under
 * [PROPORTIONAL] they carry none of it.
 */
enum class CommonSplitRule {
    EQUAL,
    PROPORTIONAL,
    ;

    companion object {
        fun fromStored(value: String?): CommonSplitRule =
            entries.firstOrNull { it.name == value } ?: EQUAL
    }
}

/**
 * The family's shared budget for the active period, one row per family.
 *
 * The pool is stored as two independent inputs, [budget] being their sum, rather than as one number.
 * The split matters because common spending and member allocations are spent at different rates and
 * are corrected by different people: the head changes the household tier when the rent changes, and
 * changes allocations when a member's habits change. Collapsing them into one figure would force
 * both edits to restate both numbers and make a mis-keyed edit unrecoverable.
 *
 * A period that has been closed keeps its pool in `BudgetPeriod.budget` and its allocations in
 * `period_limits`, so this row is overwritten rather than accumulated.
 */
@Entity(tableName = "family_state")
data class FamilyState(
    @PrimaryKey
    @ColumnInfo(name = "family_id")
    val familyId: String,

    /** Household tier plus the sum of every `period_limits.limit_value` for this period. */
    @ColumnInfo(name = "budget")
    val budget: BigDecimal,

    /** The part of [budget] reserved for spending belonging to the household rather than a person. */
    @ColumnInfo(name = "household_tier")
    val householdTier: BigDecimal,

    @ColumnInfo(name = "start_date")
    val startDate: Long,

    @ColumnInfo(name = "finish_date")
    val finishDate: Long,

    @ColumnInfo(name = "currency")
    val currency: String,

    // Default false: an individual expense is the head's business until the family opts in.
    @ColumnInfo(name = "household_detail_visible_to_all", defaultValue = "0")
    val householdDetailVisibleToAll: Boolean = false,

    @ColumnInfo(name = "common_split_rule", defaultValue = "EQUAL")
    val commonSplitRule: String = CommonSplitRule.EQUAL.name,

    // Default true, and positive tags only. See the spec's F5 for why negatives are not self-visible.
    @ColumnInfo(name = "tags_visible_to_self", defaultValue = "1")
    val tagsVisibleToSelf: Boolean = true,

    @ColumnInfo(name = "family_ai_enabled", defaultValue = "1")
    val familyAiEnabled: Boolean = true,

    @ColumnInfo(name = "sync_seq", defaultValue = "0")
    val syncSeq: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "version", defaultValue = "1")
    val version: Int = 1,
) {
    val splitRule: CommonSplitRule get() = CommonSplitRule.fromStored(commonSplitRule)

    /** The tier left for members once the household's own share is carved out. */
    val memberTier: BigDecimal get() = budget.subtract(householdTier)
}