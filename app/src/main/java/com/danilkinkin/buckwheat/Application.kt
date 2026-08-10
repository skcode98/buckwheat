package com.danilkinkin.buckwheat

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.danilkinkin.buckwheat.notifications.DailyBudgetReminderReceiver
import com.danilkinkin.buckwheat.notifications.GoalProgressNotifier
import com.danilkinkin.buckwheat.notifications.OnTrackAlertReceiver
import com.danilkinkin.buckwheat.notifications.OverspendingNotifier
import com.danilkinkin.buckwheat.notifications.RecurringPaymentAlertReceiver
import com.danilkinkin.buckwheat.notifications.SpendDigestReceiver
import com.danilkinkin.buckwheat.util.NumberDisplayConfig
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetReceiver
import com.danilkinkin.buckwheat.widget.WidgetRefreshScheduler
import androidx.work.Configuration
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetNotifications
import com.danilkinkin.buckwheat.di.roundValuesStoreKey
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class Application : Application(), Configuration.Provider {
    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()
    override fun onCreate() {
        CrashLogger.install(this)

        super.onCreate()

        configScope.launch {
            settingsDataStore.data
                .map { it[roundValuesStoreKey] ?: false }
                .distinctUntilChanged()
                .collect { NumberDisplayConfig.roundValues = it }
        }

        createNotificationChannel()

        WidgetRefreshScheduler.schedule(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

            }

            override fun onActivityStarted(activity: Activity) {

            }

            override fun onActivityResumed(activity: Activity) {

            }

            override fun onActivityPaused(activity: Activity) {
                ExtendWidgetReceiver.requestUpdateData(activity.applicationContext)
                MinimalWidgetReceiver.requestUpdateData(activity.applicationContext)
                VoiceWidgetReceiver.requestUpdateData(activity.applicationContext)
            }

            override fun onActivityStopped(activity: Activity) {

            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

            }

            override fun onActivityDestroyed(activity: Activity) {

            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                DailyBudgetReminderReceiver.CHANNEL_ID,
                getString(R.string.daily_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.daily_reminder_channel_description)
            }
            notificationManager.createNotificationChannel(channel)

            val overspendChannel = NotificationChannel(
                OverspendingNotifier.CHANNEL_ID,
                getString(R.string.overspend_notify_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.overspend_notify_channel_description)
            }
            notificationManager.createNotificationChannel(overspendChannel)

            val onTrackChannel = NotificationChannel(
                OnTrackAlertReceiver.CHANNEL_ID,
                getString(R.string.on_track_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.on_track_alert_channel_description)
            }
            notificationManager.createNotificationChannel(onTrackChannel)

            val recurringChannel = NotificationChannel(
                RecurringPaymentAlertReceiver.CHANNEL_ID,
                getString(R.string.recurring_due_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.recurring_due_channel_description)
            }
            notificationManager.createNotificationChannel(recurringChannel)

            val goalProgressChannel = NotificationChannel(
                GoalProgressNotifier.CHANNEL_ID,
                getString(R.string.goal_nudge_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.goal_nudge_channel_description)
            }
            notificationManager.createNotificationChannel(goalProgressChannel)

            val spendDigestChannel = NotificationChannel(
                SpendDigestReceiver.CHANNEL_ID,
                getString(R.string.spend_digest_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.spend_digest_channel_description)
            }
            notificationManager.createNotificationChannel(spendDigestChannel)

            val voiceWidgetChannel = NotificationChannel(
                VoiceWidgetNotifications.CHANNEL_ID,
                getString(R.string.voice_widget_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.voice_widget_channel_description)
            }
            notificationManager.createNotificationChannel(voiceWidgetChannel)
        }
    }
}
