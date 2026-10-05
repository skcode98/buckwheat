package com.danilkinkin.buckwheat.sync

object SyncTables {
    const val TRANSACTIONS = "transactions"
    const val ARCHIVED_TRANSACTIONS = "archived_transactions"
    const val BUDGET_PERIODS = "budget_periods"
    const val SAVED_CATEGORIES = "saved_categories"
    const val SAVED_TAGS = "saved_tags"
    const val RECURRING_TEMPLATES = "recurring_templates"
    const val SAVINGS_GOALS = "savings_goals"

    val ALL = listOf(
        TRANSACTIONS,
        ARCHIVED_TRANSACTIONS,
        BUDGET_PERIODS,
        SAVED_CATEGORIES,
        SAVED_TAGS,
        RECURRING_TEMPLATES,
        SAVINGS_GOALS,
    )

    /**
     * The order [SyncDatabase.apply] writes tables in, and the order [SyncBindings] binds them in.
     *
     * Parents come before children because `archived_transactions.period_id` has an ON DELETE
     * CASCADE foreign key to `budget_periods.id`. Writing a period must not delete the archived rows
     * that reference it, and an archived row cannot be inserted before its period exists. This is an
     * explicit list rather than the incidental order of a `groupBy`, so a pull cannot depend on the
     * order rows happened to arrive in the response.
     */
    val APPLY_ORDER = listOf(
        BUDGET_PERIODS,
        TRANSACTIONS,
        SAVED_CATEGORIES,
        SAVED_TAGS,
        RECURRING_TEMPLATES,
        SAVINGS_GOALS,
        ARCHIVED_TRANSACTIONS,
    )
}
