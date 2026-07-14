package com.danilkinkin.buckwheat.notifications

enum class NotificationType(
    val channelId: String,
    val requestCode: Int,
    val action: String,
    val defaultHour: Int,
    val defaultMinute: Int,
) {
    DAILY_SPEND_OVERVIEW(
        channelId = "daily_spend_overview",
        requestCode = 10,
        action = "com.danilkinkin.buckwheat.DAILY_SPEND_OVERVIEW",
        defaultHour = 20,
        defaultMinute = 0,
    ),
    WEEKLY_OVERVIEW(
        channelId = "weekly_overview",
        requestCode = 20,
        action = "com.danilkinkin.buckwheat.WEEKLY_OVERVIEW",
        defaultHour = 19,
        defaultMinute = 0,
    ),
    MONTHLY_EXPORT(
        channelId = "monthly_export",
        requestCode = 30,
        action = "com.danilkinkin.buckwheat.MONTHLY_EXPORT",
        defaultHour = 18,
        defaultMinute = 0,
    ),
    MONTHLY_OVERVIEW(
        channelId = "monthly_overview",
        requestCode = 40,
        action = "com.danilkinkin.buckwheat.MONTHLY_OVERVIEW",
        defaultHour = 18,
        defaultMinute = 0,
    ),
    FACTS_INSIGHTS(
        channelId = "facts_insights",
        requestCode = 50,
        action = "com.danilkinkin.buckwheat.FACTS_INSIGHTS",
        defaultHour = 17,
        defaultMinute = 0,
    ),
    GOALS_REMINDER(
        channelId = "goals_reminder",
        requestCode = 60,
        action = "com.danilkinkin.buckwheat.GOALS_REMINDER",
        defaultHour = 16,
        defaultMinute = 0,
    ),
    ;

    fun getChannelName(): String = when (this) {
        DAILY_SPEND_OVERVIEW -> "Daily Spend Overview"
        WEEKLY_OVERVIEW -> "Weekly Overview"
        MONTHLY_EXPORT -> "Monthly Export"
        MONTHLY_OVERVIEW -> "Monthly Overview"
        FACTS_INSIGHTS -> "Facts & Insights"
        GOALS_REMINDER -> "Goals Reminder"
    }

    fun getChannelDescription(): String = when (this) {
        DAILY_SPEND_OVERVIEW -> "Daily summary of today's spending"
        WEEKLY_OVERVIEW -> "Weekly spending recap"
        MONTHLY_EXPORT -> "Monthly CSV export notification"
        MONTHLY_OVERVIEW -> "Monthly spending breakdown"
        FACTS_INSIGHTS -> "Interesting stats and tips about your spending"
        GOALS_REMINDER -> "Reminders about your saving goals and progress"
    }

    fun getNotificationTitle(): String = when (this) {
        DAILY_SPEND_OVERVIEW -> "Daily Spend Overview"
        WEEKLY_OVERVIEW -> "Weekly Spend Overview"
        MONTHLY_EXPORT -> "Monthly Export Ready"
        MONTHLY_OVERVIEW -> "Monthly Overview"
        FACTS_INSIGHTS -> "Spending Fact"
        GOALS_REMINDER -> "Goals Reminder"
    }

    fun getNotificationText(): String = when (this) {
        DAILY_SPEND_OVERVIEW -> "Check your spending for today"
        WEEKLY_OVERVIEW -> "See how your week went"
        MONTHLY_EXPORT -> "Your monthly export is ready to download"
        MONTHLY_OVERVIEW -> "Review your monthly spending"
        FACTS_INSIGHTS -> "Here's an interesting fact about your spending"
        GOALS_REMINDER -> "Don't forget your saving goals!"
    }

    fun getScheduleDescription(hour: Int = defaultHour, minute: Int = defaultMinute): String {
        val time = String.format("%02d:%02d", hour, minute)
        return when (this) {
            DAILY_SPEND_OVERVIEW -> "Every day at $time"
            WEEKLY_OVERVIEW -> "Every Sunday at $time"
            MONTHLY_EXPORT -> "Last day of period at $time"
            MONTHLY_OVERVIEW -> "Last day of period at $time"
            FACTS_INSIGHTS -> "Every Friday at $time"
            GOALS_REMINDER -> "Every Monday at $time"
        }
    }
}
