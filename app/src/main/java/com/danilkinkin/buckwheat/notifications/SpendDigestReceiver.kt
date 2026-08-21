package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.util.Log
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_HOUR
import com.danilkinkin.buckwheat.di.SPEND_DIGEST_DEFAULT_MINUTE
import com.danilkinkin.buckwheat.di.currencyStoreKey
import com.danilkinkin.buckwheat.di.spendDigestFrequencyStoreKey
import com.danilkinkin.buckwheat.di.spendDigestHourStoreKey
import com.danilkinkin.buckwheat.di.spendDigestMinuteStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

// Periodic (weekly/monthly) spending summary. Uses the same one-shot setWindow + re-arm pattern
// as the daily budget reminder, and re-arms the next period after every fire using the stored
// frequency and time.
@AndroidEntryPoint
class SpendDigestReceiver : BroadcastReceiver() {
    @Inject
    lateinit var transactionDao: TransactionDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SpendDigestScheduler.ACTION_SPEND_DIGEST) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postDigest(context)
                rearm(context)
            } catch (e: Exception) {
                Log.e("SpendDigestReceiver", "Receiver failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun rearm(context: Context) {
        val prefs = context.settingsDataStore.data.first()
        val hour = prefs[spendDigestHourStoreKey] ?: SPEND_DIGEST_DEFAULT_HOUR
        val minute = prefs[spendDigestMinuteStoreKey] ?: SPEND_DIGEST_DEFAULT_MINUTE
        val frequency = readFrequency(prefs[spendDigestFrequencyStoreKey])
        SpendDigestScheduler.schedule(context, hour, minute, frequency)
    }

    private suspend fun postDigest(context: Context) {
        val settings = context.settingsDataStore.data.first()
        val frequency = readFrequency(settings[spendDigestFrequencyStoreKey])
        val currency = context.budgetDataStore.data.first()[currencyStoreKey]
            ?.let { ExtendCurrency.getInstance(it) }
            ?: ExtendCurrency.none()

        val (from, to) = digestRange(frequency)
        val zone = ZoneId.systemDefault()
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val spends = transactionDao
            .getAll(TransactionType.SPENT, fromMillis, toMillis)
            .first()
        val digest = buildSpendDigest(spends, from, to)
        if (digest.transactionCount == 0) return

        val message = buildSpendDigestMessage(context, digest, frequency, currency)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SpendDigestScheduler.NOTIFICATION_ID,
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
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        notificationManager.notify(SpendDigestScheduler.NOTIFICATION_ID, notification)
    }

    private fun readFrequency(raw: String?): SpendDigestFrequency =
        runCatching { SpendDigestFrequency.valueOf(raw ?: "") }
            .getOrDefault(SpendDigestFrequency.WEEKLY)

    companion object {
        const val CHANNEL_ID = "spend_digest"
    }
}
