package com.danilkinkin.buckwheat.settings

import androidx.compose.runtime.Composable
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.di.onTrackAlertEnabledStoreKey
import com.danilkinkin.buckwheat.di.onTrackAlertHourStoreKey
import com.danilkinkin.buckwheat.di.onTrackAlertMinuteStoreKey
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_HOUR
import com.danilkinkin.buckwheat.notifications.DAILY_REMINDER_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.notifications.OnTrackAlertScheduler

@Composable
fun OnTrackAlertSetting() {
    NotificationToggleSetting(
        iconRes = R.drawable.ic_notifications,
        titleRes = R.string.on_track_alert_setting_title,
        timeLabelRes = R.string.on_track_alert_time,
        enabledKey = onTrackAlertEnabledStoreKey,
        hourKey = onTrackAlertHourStoreKey,
        minuteKey = onTrackAlertMinuteStoreKey,
        defaultHour = DAILY_REMINDER_DEFAULT_HOUR,
        defaultMinute = DAILY_REMINDER_DEFAULT_MINUTE,
        schedule = { ctx, h, m -> OnTrackAlertScheduler.schedule(ctx, h, m) },
        cancel = { ctx -> OnTrackAlertScheduler.cancel(ctx) },
    )
}
