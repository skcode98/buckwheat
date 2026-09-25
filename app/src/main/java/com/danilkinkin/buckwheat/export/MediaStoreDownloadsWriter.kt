package com.danilkinkin.buckwheat.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

// Writes a CSV payload to the shared Downloads collection via MediaStore. Scoped storage needs
// no permission for the app's own writes (min SDK 29); the file becomes visible to other apps
// once IS_PENDING is cleared.
object MediaStoreDownloadsWriter {
    fun writeCsv(context: Context, displayName: String, content: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null

        val wrote = try {
            val stream = context.contentResolver.openOutputStream(uri)
            if (stream == null) {
                context.contentResolver.delete(uri, null, null)
                false
            } else {
                stream.use { output ->
                    val writer = output.writer()
                    writer.write(content)
                    writer.flush()
                }
                true
            }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            false
        }
        if (!wrote) return null

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }
}