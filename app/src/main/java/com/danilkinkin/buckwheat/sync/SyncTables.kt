package com.danilkinkin.buckwheat.sync

object SyncTables {
    const val TRANSACTIONS = "transactions"
    const val ARCHIVED_TRANSACTIONS = "archived_transactions"
    const val BUDGET_PERIODS = "budget_periods"
    const val SAVED_CATEGORIES = "saved_categories"
    const val SAVED_TAGS = "saved_tags"
    const val RECURRING_TEMPLATES = "recurring_templates"
    const val SAVINGS_GOALS = "savings_goals"
    const val FAMILY_STATE = "family_state"
    const val PERIOD_LIMITS = "period_limits"
    const val SPEND_ASSIGNMENTS = "spend_assignments"

    val ALL = listOf(
        TRANSACTIONS,
        ARCHIVED_TRANSACTIONS,
        BUDGET_PERIODS,
        SAVED_CATEGORIES,
        SAVED_TAGS,
        RECURRING_TEMPLATES,
        SAVINGS_GOALS,
        FAMILY_STATE,
        PERIOD_LIMITS,
        SPEND_ASSIGNMENTS,
    )

    /**
     * The order [SyncDatabase.apply] writes tables in, and the order [SyncBindings] binds them in.
     *
     * Parents come before children because `archived_transactions.period_id` has an ON DELETE
     * CASCADE foreign key to `budget_periods.id`. Writing a period must not delete the archived rows
     * that reference it, and an archived row cannot be inserted before its period exists. This is an
     * explicit list rather than the incidental order of a `groupBy`, so a pull cannot depend on the
     * order rows happened to arrive in the response.
     *
     * `family_state` comes before `period_limits` and `spend_assignments` for the same reason one step
     * removed: both carry a `family_id` that a pull populates, and the server resolves who may write
     * them from that. Writing them before the pool exists would mean an assignment arrived with
     * nothing to attach to.
     */
    val APPLY_ORDER = listOf(
        BUDGET_PERIODS,
        TRANSACTIONS,
        SAVED_CATEGORIES,
        SAVED_TAGS,
        RECURRING_TEMPLATES,
        SAVINGS_GOALS,
        FAMILY_STATE,
        PERIOD_LIMITS,
        SPEND_ASSIGNMENTS,
        ARCHIVED_TRANSACTIONS,
    )
}
