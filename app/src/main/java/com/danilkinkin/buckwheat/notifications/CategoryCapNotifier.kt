package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_NEAR_PERCENT
import com.danilkinkin.buckwheat.data.categories.CATEGORY_CAP_REACHED_BUCKET
import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.categoryCapPercent
import com.danilkinkin.buckwheat.util.numberFormat
import java.math.BigDecimal

// Instant notification posted the moment a new spend pushes a category at/above 80% or 100%
// of its configured cap. Fires at most once per crossing (tracked per category in settings),
// mirroring the daily overspend notification.
object CategoryCapNotifier {
    const val CHANNEL_ID = "category_cap"
    const val NOTIFICATION_ID = 300

    fun notify(
        context: Context,
        key: CategoryKey,
        bucket: Int,
        progress: BigDecimal,
        cap: BigDecimal,
        currency: ExtendCurrency,
    ) {
        val categoryName = when (key) {
            is CategoryKey.BuiltIn -> context.getString(key.category.labelRes)
            is CategoryKey.Custom -> key.name
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val notification = if (bucket >= CATEGORY_CAP_REACHED_BUCKET) {
            builder
                .setContentTitle(context.getString(R.string.category_cap_reached_title))
                .setContentText(
                    context.getString(
                        R.string.category_cap_reached_text,
                        categoryName,
                        numberFormat(context, cap, currency),
                    )
                )
                .build()
        } else {
            builder
                .setContentTitle(context.getString(R.string.category_cap_near_title))
                .setContentText(
                    context.getString(
                        R.string.category_cap_near_text,
                        categoryName,
                        categoryCapPercent(progress, cap).coerceAtMost(CATEGORY_CAP_NEAR_PERCENT),
                        numberFormat(context, cap, currency),
                    )
                )
                .build()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
