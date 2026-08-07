package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal
import java.math.RoundingMode

// Instant notification posted the moment a new spend pushes today's spending over the daily
// budget. Unlike the scheduled daily reminder this fires immediately, so the user learns of
// the overspend while it happens instead of at the configured reminder time.
object OverspendingNotifier {
    const val CHANNEL_ID = "overspending"
    const val NOTIFICATION_ID = 200

    fun notify(
        context: Context,
        dailyBudget: BigDecimal,
        spentFromDailyBudget: BigDecimal,
        currency: ExtendCurrency,
    ) {
        val amountOver = (spentFromDailyBudget - dailyBudget)
            .setScale(2, RoundingMode.HALF_EVEN)
            .stripTrailingZeros()

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.overspend_notify_title))
            .setContentText(
                context.getString(
                    R.string.overspend_notify_text,
                    numberFormat(context, amountOver, currency),
                    numberFormat(context, dailyBudget, currency),
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
