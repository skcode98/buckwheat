package com.danilkinkin.buckwheat.recurring

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.room.Room
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.DatabaseModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

class RecurringReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    DatabaseModule::class.java,
                    "buckwheat-db",
                )
                    .fallbackToDestructiveMigration(false)
                    .build()

                val recurringDao = db.recurringDao()
                val transactionDao = db.transactionDao()

                val today = Calendar.getInstance()
                val dayOfMonth = today.get(Calendar.DAY_OF_MONTH)
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time

                val dueTemplates = recurringDao.getDueOnDay(dayOfMonth)
                var created = 0

                for (template in dueTemplates) {
                    val existing = transactionDao.getAll(TransactionType.SPENT).asFlow().first().any { t ->
                        t.value == template.amount
                                && t.comment == template.comment
                                && isSameDay(t.date, todayStart)
                    }

                    if (!existing) {
                        transactionDao.insert(
                            Transaction(
                                type = TransactionType.SPENT,
                                value = template.amount,
                                date = Date(),
                                comment = template.comment,
                            )
                        )
                        created++
                    }
                }

                db.close()

                if (created > 0) {
                    showNotification(context, created)
                }
            } catch (e: Exception) {
                Log.e("RecurringReceiver", "Failed to process recurring", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            context, RECURRING_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(
                context.getString(R.string.recurring_notification_title)
            )
            .setContentText(
                context.getString(R.string.recurring_notification_text, count)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, notification)
    }

    companion object {
        const val RECURRING_CHANNEL_ID = "recurring_transactions"
    }
}

private fun isSameDay(date1: Date, date2: Date): Boolean {
    val c1 = Calendar.getInstance().apply { time = date1 }
    val c2 = Calendar.getInstance().apply { time = date2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
            && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}
