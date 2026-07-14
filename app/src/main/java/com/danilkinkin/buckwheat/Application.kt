package com.danilkinkin.buckwheat

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.danilkinkin.buckwheat.notifications.NotificationType
import com.danilkinkin.buckwheat.recurring.RecurringReceiver
import com.danilkinkin.buckwheat.reminder.ReminderReceiver
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class Application : Application() {
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

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
            val notificationManager =
                getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                ReminderReceiver.CHANNEL_ID,
                getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.reminder_channel_description)
            }
            notificationManager.createNotificationChannel(channel)

            val recurringChannel = NotificationChannel(
                RecurringReceiver.RECURRING_CHANNEL_ID,
                getString(R.string.recurring_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.recurring_channel_description)
            }
            notificationManager.createNotificationChannel(recurringChannel)

            NotificationType.entries.forEach { type ->
                val notificationChannel = NotificationChannel(
                    type.channelId,
                    type.getChannelName(),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = type.getChannelDescription()
                }
                notificationManager.createNotificationChannel(notificationChannel)
            }
        }
    }
}
