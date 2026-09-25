package com.danilkinkin.buckwheat.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.autoExportEnabledStoreKey
import com.danilkinkin.buckwheat.di.finishPeriodActualDateStoreKey
import com.danilkinkin.buckwheat.di.finishPeriodDateStoreKey
import com.danilkinkin.buckwheat.di.lastAutoExportedPeriodStartStoreKey
import com.danilkinkin.buckwheat.di.startPeriodDateStoreKey
import com.danilkinkin.buckwheat.export.MediaStoreDownloadsWriter
import com.danilkinkin.buckwheat.export.buildAutoExportFileName
import com.danilkinkin.buckwheat.export.buildPeriodCsv
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.toLocalDate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

// Silently saves the finished budget period's transactions as a CSV into Downloads and posts a
// success notification. One-shot: the toggle re-schedules the alarm whenever the finish date
// changes and a dedup key guards against double export for the same period.
@AndroidEntryPoint
class AutoExportReceiver : BroadcastReceiver() {
    @Inject
    lateinit var transactionDao: TransactionDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoExportScheduler.ACTION_AUTO_EXPORT) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                exportPeriod(context)
            } catch (e: Exception) {
                Log.e("AutoExportReceiver", "Receiver failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun exportPeriod(context: Context) {
        val settings = context.settingsDataStore.data.first()
        if (!(settings[autoExportEnabledStoreKey] ?: false)) return

        val budget = context.budgetDataStore.data.first()
        val startMillis = budget[startPeriodDateStoreKey] ?: return
        val finishMillis = budget[finishPeriodDateStoreKey] ?: return

        if (!shouldAutoExportPeriod(
                toggleEnabled = settings[autoExportEnabledStoreKey] ?: false,
                finishPeriodActualDateMillis = budget[finishPeriodActualDateStoreKey],
                startPeriodMillis = startMillis,
                finishPeriodMillis = finishMillis,
                lastAutoExportedPeriodStart = budget[lastAutoExportedPeriodStartStoreKey],
                nowMillis = Date().time,
            )
        ) return

        val spends = transactionDao
            .getAllNow(TransactionType.SPENT, startMillis, finishMillis)
        if (spends.isEmpty()) return

        val csv = buildPeriodCsv(spends)
        val fileName = buildAutoExportFileName(
            context.getString(R.string.export_to_csv_file_name),
            Date(startMillis).toLocalDate(),
            Date(finishMillis).toLocalDate(),
        )
        val uri = MediaStoreDownloadsWriter.writeCsv(context, fileName, csv) ?: return

        context.budgetDataStore.edit {
            it[lastAutoExportedPeriodStartStoreKey] = startMillis
        }

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            AutoExportScheduler.NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.auto_export_notify_title))
            .setContentText(context.getString(R.string.auto_export_notify_text, fileName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        notificationManager.notify(AutoExportScheduler.NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "auto_export"
    }
}