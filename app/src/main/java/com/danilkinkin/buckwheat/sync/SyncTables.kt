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
}
