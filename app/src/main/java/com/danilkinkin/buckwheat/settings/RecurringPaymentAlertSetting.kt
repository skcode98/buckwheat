package com.danilkinkin.buckwheat.settings

import androidx.compose.runtime.Composable
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.recurringAlertEnabledStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertHourStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertMinuteStoreKey
import com.danilkinkin.buckwheat.notifications.RecurringPaymentAlertScheduler

@Composable
fun RecurringPaymentAlertSetting() {
    NotificationToggleSetting(
        iconRes = R.drawable.ic_autorenew,
        titleRes = R.string.recurring_alert_setting_title,
        timeLabelRes = R.string.recurring_alert_time,
        enabledKey = recurringAlertEnabledStoreKey,
        hourKey = recurringAlertHourStoreKey,
        minuteKey = recurringAlertMinuteStoreKey,
        defaultHour = RECURRING_ALERT_DEFAULT_HOUR,
        defaultMinute = RECURRING_ALERT_DEFAULT_MINUTE,
        schedule = { ctx, h, m -> RecurringPaymentAlertScheduler.schedule(ctx, h, m) },
        cancel = { ctx -> RecurringPaymentAlertScheduler.cancel(ctx) },
    )
}
