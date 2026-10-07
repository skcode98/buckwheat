package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

val syncMembersStoreKey = stringPreferencesKey("syncFamilyMembers")

/**
 * The roster, cached in preferences rather than in a Room table. Every transaction carries a
 * `memberId` that means nothing on its own, so this list is the only thing that can turn a synced row
 * into a name, and it has to survive a cold offline start. A room table would mean a migration.
 */
interface FamilyMembersCache {
    fun members(): Flow<List<FamilyMember>>
    suspend fun readMembers(): List<FamilyMember>
    suspend fun replaceMembers(members: List<FamilyMember>)
    suspend fun clear()

    /** memberId to display name, which is what an already synced `Transaction.memberId` resolves to. */
    fun memberNames(): Flow<Map<String, String>> =
        members().map { roster -> roster.associate { it.id to it.displayName } }
}

class DataStoreFamilyMembersCache @Inject constructor(
    @ApplicationContext private val context: Context,
) : FamilyMembersCache {

    private val store: DataStore<Preferences>
        get() = context.syncStateDataStore

    override fun members(): Flow<List<FamilyMember>> =
        store.data.map { decodeCachedMembers(it[syncMembersStoreKey]) }

    override suspend fun readMembers(): List<FamilyMember> = members().first()

    override suspend fun replaceMembers(members: List<FamilyMember>) {
        store.edit { it[syncMembersStoreKey] = encodeCachedMembers(members) }
    }

    override suspend fun clear() {
        store.edit { it.remove(syncMembersStoreKey) }
    }
}

internal fun encodeCachedMembers(members: List<FamilyMember>): String {
    val array = JSONArray()
    members.forEach { member ->
        array.put(
            JSONObject()
                .put("id", member.id)
                .put("displayName", member.displayName)
                .put("departed", member.departed)
                .put("joinedAt", member.joinedAt)
        )
    }
    return array.toString()
}

/**
 * Unlike the network decoder this never throws. A truncated write or a value written by an older
 * build must degrade to an empty roster, because the alternative is a settings screen that cannot be
 * opened at all, which is a far worse failure than showing no members.
 */
internal fun decodeCachedMembers(raw: String?): List<FamilyMember> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(raw)
    } catch (e: JSONException) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optNullableString("id")?.takeIf { it.isNotBlank() } ?: continue
            val displayName = entry.optNullableString("displayName")?.takeIf { it.isNotBlank() } ?: continue
            add(
                FamilyMember(
                    id = id,
                    displayName = displayName,
                    departed = entry.optBoolean("departed", false),
                    joinedAt = entry.optNullableString("joinedAt").orEmpty(),
                )
            )
        }
    }
}