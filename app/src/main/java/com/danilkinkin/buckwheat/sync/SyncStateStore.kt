package com.danilkinkin.buckwheat.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

val Context.syncStateDataStore by preferencesDataStore("syncState")

val syncCursorStoreKey = longPreferencesKey("syncCursor")
val syncConflictsStoreKey = stringPreferencesKey("syncConflicts")

interface SyncStateStore {
    fun cursor(): Flow<Long>
    suspend fun readCursor(): Long
    suspend fun writeCursor(cursor: Long)
    fun conflicts(): Flow<List<ConflictNotice>>
    suspend fun readConflicts(): List<ConflictNotice>
    suspend fun replaceConflicts(conflicts: List<ConflictNotice>)
    suspend fun clear()
}

class DataStoreSyncStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncStateStore {

    private val store: DataStore<Preferences>
        get() = context.syncStateDataStore

    override fun cursor(): Flow<Long> = store.data.map { it[syncCursorStoreKey] ?: 0L }

    override suspend fun readCursor(): Long = cursor().first()

    override suspend fun writeCursor(cursor: Long) {
        store.edit { it[syncCursorStoreKey] = cursor }
    }

    override fun conflicts(): Flow<List<ConflictNotice>> =
        store.data.map { decodeConflicts(it[syncConflictsStoreKey]) }

    override suspend fun readConflicts(): List<ConflictNotice> = conflicts().first()

    override suspend fun replaceConflicts(conflicts: List<ConflictNotice>) {
        store.edit { it[syncConflictsStoreKey] = encodeConflicts(conflicts) }
    }

    override suspend fun clear() {
        store.edit {
            it[syncCursorStoreKey] = 0L
            it.remove(syncConflictsStoreKey)
        }
    }
}

internal fun encodeConflicts(conflicts: List<ConflictNotice>): String {
    val array = JSONArray()
    conflicts.forEach { notice ->
        array.put(
            JSONObject()
                .put("table", notice.table)
                .put("id", notice.id)
                .put("wonByMemberId", notice.wonByMemberId ?: JSONObject.NULL)
                .put("reason", notice.reason.wire)
        )
    }
    return array.toString()
}

internal fun decodeConflicts(raw: String?): List<ConflictNotice> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(raw)
    } catch (e: JSONException) {
        return emptyList()
    }
    return buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val table = obj.optNullableString("table").orEmpty()
            val id = obj.optNullableString("id").orEmpty()
            // Only an unusable key discards an entry. A missing or null winner is the CORRECT reading
            // for the memberless tables, whose rows are shared by the family rather than owned by a
            // member, and dropping those notices is what used to hide every conflict on five of the
            // seven tables.
            if (table.isBlank() || id.isBlank()) continue
            add(
                ConflictNotice(
                    table = table,
                    id = id,
                    wonByMemberId = obj.optNullableString("wonByMemberId")?.takeIf { it.isNotBlank() },
                    reason = ConflictReason.fromWire(obj.optNullableString("reason")),
                )
            )
        }
    }
}
