package com.danilkinkin.buckwheat.settings

import androidx.compose.runtime.Composable
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.di.reminderEnabledStoreKey
import com.danilkinkin.buckwheat.di.reminderHourStoreKey
import com.danilkinkin.buckwheat.di.reminderMinuteStoreKey
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.DailyBudgetReminderScheduler

@Composable
fun DailyBudgetReminderSetting() {
    NotificationToggleSetting(
        iconRes = R.drawable.ic_notifications,
        titleRes = R.string.daily_reminder_title,
        timeLabelRes = R.string.daily_reminder_time,
        enabledKey = reminderEnabledStoreKey,
        hourKey = reminderHourStoreKey,
        minuteKey = reminderMinuteStoreKey,
        defaultHour = DAILY_REMINDER_DEFAULT_HOUR,
        defaultMinute = DAILY_REMINDER_DEFAULT_MINUTE,
        schedule = { ctx, h, m -> DailyBudgetReminderScheduler.schedule(ctx, h, m) },
        cancel = { ctx -> DailyBudgetReminderScheduler.cancel(ctx) },
    )
}
