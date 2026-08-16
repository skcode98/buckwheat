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
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.di.currencyStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertHourStoreKey
import com.danilkinkin.buckwheat.di.recurringAlertMinuteStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.roundToDay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// Daily alert listing recurring payments due today. Uses the same one-shot setWindow + re-arm
// pattern as the daily budget reminder, and reads the recurring templates from Room.
@AndroidEntryPoint
class RecurringPaymentAlertReceiver : BroadcastReceiver() {
    @Inject
    lateinit var recurringDao: RecurringDao

    @Inject
    lateinit var transactionDao: TransactionDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecurringPaymentAlertScheduler.ACTION_RECURRING_ALERT) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postDuePaymentAlert(context)
                // One-shot: re-arm the next day's alert at the stored time.
                rearmNextDay(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rearmNextDay(context: Context) {
        val prefs = context.settingsDataStore.data.first()
        val hour = prefs[recurringAlertHourStoreKey] ?: com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_HOUR
        val minute = prefs[recurringAlertMinuteStoreKey] ?: com.danilkinkin.buckwheat.di.RECURRING_ALERT_DEFAULT_MINUTE
        RecurringPaymentAlertScheduler.schedule(context, hour, minute)
    }

    private suspend fun postDuePaymentAlert(context: Context) {
        val today = roundToDay(Calendar.getInstance().time)
        val dayOfMonth = Calendar.getInstance().apply { time = today }
            .get(Calendar.DAY_OF_MONTH)
        val dueTemplates = recurringDao.getDueOnDay(dayOfMonth)
            .let { filterAlreadyRecorded(it, transactionDao.getAllNow(), today) }
        if (dueTemplates.isEmpty()) return

        val currency = context.budgetDataStore.data.first()[currencyStoreKey]
            ?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            RecurringPaymentAlertScheduler.NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.recurring_due_notification_title))
            .setContentText(buildRecurringDueText(context, dueTemplates, currency))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildRecurringDueText(context, dueTemplates, currency)
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        notificationManager.notify(RecurringPaymentAlertScheduler.NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "recurring_due"
    }
}
