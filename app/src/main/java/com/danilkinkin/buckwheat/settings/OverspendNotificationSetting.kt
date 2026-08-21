package com.danilkinkin.buckwheat.settings

import androidx.compose.runtime.Composable
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.di.overspendNotifyEnabledStoreKey

@Composable
fun OverspendNotificationSetting() {
    NotificationToggleSetting(
        iconRes = R.drawable.ic_priority_high,
        titleRes = R.string.overspend_notify_setting_title,
        enabledKey = overspendNotifyEnabledStoreKey,
    )
}
