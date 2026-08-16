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
import com.danilkinkin.buckwheat.di.onTrackAlertEnabledStoreKey
import com.danilkinkin.buckwheat.di.onTrackAlertHourStoreKey
import com.danilkinkin.buckwheat.di.onTrackAlertMinuteStoreKey
import com.danilkinkin.buckwheat.di.spentFromDailyBudgetStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.numberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

class OnTrackAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OnTrackAlertScheduler.ACTION_ON_TRACK_ALERT) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postAlert(context)
                // setWindow is one-shot: re-arm the next day's alert at the stored time while
                // the toggle is still on.
                rearmNextDay(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rearmNextDay(context: Context) {
        val prefs = context.settingsDataStore.data.first()
        if (prefs[onTrackAlertEnabledStoreKey] != true) return
        val hour = prefs[onTrackAlertHourStoreKey] ?: DAILY_REMINDER_DEFAULT_HOUR
        val minute = prefs[onTrackAlertMinuteStoreKey] ?: DAILY_REMINDER_DEFAULT_MINUTE
        OnTrackAlertScheduler.schedule(context, hour, minute)
    }

    private suspend fun postAlert(context: Context) {
        val prefs = context.budgetDataStore.data.first()

        val dailyBudget = (prefs[dailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_EVEN)
        val spentFromDailyBudget =
            (prefs[spentFromDailyBudgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN)

        val now = Calendar.getInstance()
        val elapsedMinutes =
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val message = buildOnTrackAlertMessage(dailyBudget, spentFromDailyBudget, elapsedMinutes)
        // Only surface a proactive warning; the instant overspend notification handles the rest.
        if (message.kind == OnTrackAlertKind.NONE || message.kind == OnTrackAlertKind.NO_DAILY_BUDGET) {
            return
        }

        val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            OnTrackAlertScheduler.NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val projected = numberFormat(context, message.projected, currency)
        val budget = numberFormat(context, message.dailyBudget, currency)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.on_track_alert_notification_title))
            .setContentText(
                context.getString(R.string.on_track_alert_notification_text, projected, budget)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        notificationManager.notify(OnTrackAlertScheduler.NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "on_track_alert"
    }
}