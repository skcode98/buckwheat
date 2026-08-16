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
import com.danilkinkin.buckwheat.di.budgetStoreKey
import com.danilkinkin.buckwheat.di.currencyStoreKey
import com.danilkinkin.buckwheat.di.finishPeriodActualDateStoreKey
import com.danilkinkin.buckwheat.di.finishPeriodDateStoreKey
import com.danilkinkin.buckwheat.di.spentFromDailyBudgetStoreKey
import com.danilkinkin.buckwheat.di.spentStoreKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Date

// Notifies at the natural end of a budget period with the amount left over. One-shot: the
// ViewModel re-schedules the alarm whenever the budget (and thus the finish date) changes.
class PeriodFinishReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeriodFinishScheduler.ACTION_PERIOD_FINISH) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postNotification(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun postNotification(context: Context) {
        val prefs = context.budgetDataStore.data.first()

        // Period already finished manually — the in-app summary covered it.
        if (prefs[finishPeriodActualDateStoreKey] != null) return

        // Guard against a stale alarm firing after the user already started a new period:
        // only notify while the stored finish moment is in the past.
        val finishDateMillis = prefs[finishPeriodDateStoreKey] ?: return
        if (finishDateMillis > Date().time) return

        val budget = prefs[budgetStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
        val spent = prefs[spentStoreKey]?.toBigDecimal() ?: BigDecimal.ZERO
        val spentFromDailyBudget = prefs[spentFromDailyBudgetStoreKey]?.toBigDecimal()
            ?: BigDecimal.ZERO
        val currency = prefs[currencyStoreKey]?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()

        val message = buildPeriodFinishMessage(
            context,
            budget - spent - spentFromDailyBudget,
            currency,
        )

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PeriodFinishScheduler.NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(message.title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(PeriodFinishScheduler.NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "period_finish"
    }
}
