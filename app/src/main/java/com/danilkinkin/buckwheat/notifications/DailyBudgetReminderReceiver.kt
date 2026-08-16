package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.di.currencyStoreKey
import com.danilkinkin.buckwheat.di.dailyBudgetStoreKey
import com.danilkinkin.buckwheat.di.reminderHourStoreKey
import com.danilkinkin.buckwheat.di.reminderMinuteStoreKey
import com.danilkinkin.buckwheat.di.spentFromDailyBudgetStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.numberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class DailyBudgetReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyBudgetReminderScheduler.ACTION_DAILY_REMINDER) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postNotification(context)
                // setWindow is one-shot: re-arm the next day's reminder at the stored time.
                rearmNextDay(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rearmNextDay(context: Context) {
        val prefs = context.settingsDataStore.data.first()
        val hour = prefs[reminderHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
        val minute = prefs[reminderMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
        DailyBudgetReminderScheduler.schedule(context, hour, minute)
    }

    private suspend fun postNotification(context: Context) {
        val prefs = context.budgetDataStore.data.first()

        val dailyBudget = (prefs[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_EVEN)
        val spentFromDailyBudget =
            (prefs[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN)
        val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()

        val message = buildDailyReminderMessage(dailyBudget, spentFromDailyBudget)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            DailyBudgetReminderScheduler.NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.daily_reminder_notification_title))
            .setContentText(buildNotificationText(context, message, currency))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        notificationManager.notify(DailyBudgetReminderScheduler.NOTIFICATION_ID, notification)
    }

    private fun buildNotificationText(
        context: Context,
        message: DailyReminderMessage,
        currency: ExtendCurrency,
    ): String {
        val amount = numberFormat(context, message.amount, currency)
        return when (message.kind) {
            DailyReminderMessageKind.LEFT ->
                context.getString(R.string.daily_reminder_left_text, amount)
            DailyReminderMessageKind.OVER ->
                context.getString(R.string.daily_reminder_over_text, amount)
            DailyReminderMessageKind.NO_BUDGET ->
                context.getString(R.string.daily_reminder_no_budget_text)
        }
    }

    companion object {
        const val CHANNEL_ID = "daily_budget_reminder"
    }
}
