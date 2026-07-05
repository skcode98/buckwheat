package com.danilkinkin.buckwheat.sync

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.asFlow
import androidx.room.Room
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.isSameDay
import com.danilkinkin.buckwheat.util.roundToDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncManager.ACTION_EXPORT_SPENDS) return

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

                val dao = db.transactionDao()
                val allSpends = dao.getAll(TransactionType.SPENT).asFlow().first()
                val today = roundToDay(Date())

                val todaySpends = allSpends.filter {
                    isSameDay(it.date, today)
                }

                if (todaySpends.isNotEmpty()) {
                    val dateFormatter = SimpleDateFormat("dd/MM/yy, hh:mm a", Locale.US)
                    val csvContent = buildString {
                        appendLine("amount,comment,commit_time")
                        todaySpends.forEach { t ->
                            val comment = t.comment.replace("\"", "\"\"")
                            appendLine("${t.value},\"$comment\",${dateFormatter.format(t.date)}")
                        }
                    }

                    deleteExistingSyncFiles(context)

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "buckwheat_sync.csv")
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Buckwheat"
                        )
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        contentValues
                    )

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(csvContent.toByteArray(Charsets.UTF_8))
                        }
                    }
                }

                db.close()
            } catch (e: Exception) {
                Log.e("SyncReceiver", "Export failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun deleteExistingSyncFiles(context: Context) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("buckwheat_sync.csv")
        context.contentResolver.delete(collection, selection, selectionArgs)
    }
}
