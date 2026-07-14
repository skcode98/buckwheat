package com.danilkinkin.buckwheat.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object NotificationScheduler {

    fun scheduleDailyOverview(context: Context, hour: Int = 20, minute: Int = 0) {
        scheduleRepeating(
            context = context,
            type = NotificationType.DAILY_SPEND_OVERVIEW,
            hour = hour,
            minute = minute,
            interval = AlarmManager.INTERVAL_DAY,
        )
    }

    fun cancelDailyOverview(context: Context) {
        cancel(context, NotificationType.DAILY_SPEND_OVERVIEW)
    }

    fun scheduleWeeklyOverview(context: Context, hour: Int = 19, minute: Int = 0) {
        scheduleWeekly(
            context = context,
            type = NotificationType.WEEKLY_OVERVIEW,
            dayOfWeek = Calendar.SUNDAY,
            hour = hour,
            minute = minute,
        )
    }

    fun cancelWeeklyOverview(context: Context) {
        cancel(context, NotificationType.WEEKLY_OVERVIEW)
    }

    fun scheduleMonthlyExport(context: Context, hour: Int = 18, minute: Int = 0) {
        scheduleMonthly(
            context = context,
            type = NotificationType.MONTHLY_EXPORT,
            hour = hour,
            minute = minute,
        )
    }

    fun cancelMonthlyExport(context: Context) {
        cancel(context, NotificationType.MONTHLY_EXPORT)
    }

    fun scheduleMonthlyOverview(context: Context, hour: Int = 18, minute: Int = 0) {
        scheduleMonthly(
            context = context,
            type = NotificationType.MONTHLY_OVERVIEW,
            hour = hour,
            minute = minute,
        )
    }

    fun cancelMonthlyOverview(context: Context) {
        cancel(context, NotificationType.MONTHLY_OVERVIEW)
    }

    fun scheduleFactsInsights(context: Context, hour: Int = 17, minute: Int = 0) {
        scheduleWeekly(
            context = context,
            type = NotificationType.FACTS_INSIGHTS,
            dayOfWeek = Calendar.FRIDAY,
            hour = hour,
            minute = minute,
        )
    }

    fun cancelFactsInsights(context: Context) {
        cancel(context, NotificationType.FACTS_INSIGHTS)
    }

    fun scheduleGoalsReminder(context: Context, hour: Int = 16, minute: Int = 0) {
        scheduleWeekly(
            context = context,
            type = NotificationType.GOALS_REMINDER,
            dayOfWeek = Calendar.MONDAY,
            hour = hour,
            minute = minute,
        )
    }

    fun cancelGoalsReminder(context: Context) {
        cancel(context, NotificationType.GOALS_REMINDER)
    }

    private fun scheduleRepeating(
        context: Context,
        type: NotificationType,
        hour: Int,
        minute: Int,
        interval: Long,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = type.action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            interval,
            pendingIntent,
        )
    }

    private fun scheduleMonthly(
        context: Context,
        type: NotificationType,
        hour: Int,
        minute: Int,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = type.action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            if (before(Calendar.getInstance())) {
                add(Calendar.MONTH, 1)
            }
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent,
        )
    }

    private fun scheduleWeekly(
        context: Context,
        type: NotificationType,
        dayOfWeek: Int,
        hour: Int,
        minute: Int,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = type.action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            if (before(Calendar.getInstance())) {
                add(Calendar.WEEK_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY * 7,
            pendingIntent,
        )
    }

    fun cancel(context: Context, type: NotificationType) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = type.action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            type.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }
}
