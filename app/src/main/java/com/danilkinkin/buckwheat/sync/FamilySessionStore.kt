package com.danilkinkin.buckwheat.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val syncBaseUrlStoreKey = stringPreferencesKey("syncBaseUrl")
val syncTokenStoreKey = stringPreferencesKey("syncToken")
val syncFamilyIdStoreKey = stringPreferencesKey("syncFamilyId")
val syncMemberIdStoreKey = stringPreferencesKey("syncMemberId")
val syncJoinCodeStoreKey = stringPreferencesKey("syncFamilyJoinCode")

data class FamilySession(
    val baseUrl: String,
    val token: String,
    val familyId: String,
    val memberId: String,
    val joinCode: String,
)

interface FamilySessionStore {
    fun session(): Flow<FamilySession?>

    fun members(): Flow<List<FamilyMember>> = flowOf(emptyList())

    suspend fun current(): FamilySession?

    suspend fun token(): String?

    suspend fun baseUrl(): String?

    suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String = "")

    suspend fun setBaseUrl(baseUrl: String)

    suspend fun clear()
}

class DataStoreFamilySessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val membersCache: FamilyMembersCache,
) : FamilySessionStore {

    override fun session(): Flow<FamilySession?> = context.settingsDataStore.data.map { prefs ->
        val baseUrl = prefs[syncBaseUrlStoreKey].orEmpty()
        val token = prefs[syncTokenStoreKey].orEmpty()
        val familyId = prefs[syncFamilyIdStoreKey].orEmpty()
        val memberId = prefs[syncMemberIdStoreKey].orEmpty()
        if (baseUrl.isBlank() || token.isBlank() || familyId.isBlank() || memberId.isBlank()) {
            null
        } else {
            FamilySession(
                baseUrl = baseUrl,
                token = token,
                familyId = familyId,
                memberId = memberId,
                joinCode = prefs[syncJoinCodeStoreKey].orEmpty(),
            )
        }
    }

    override fun members(): Flow<List<FamilyMember>> = membersCache.members()

    override suspend fun current(): FamilySession? = session().first()

    override suspend fun token(): String? = current()?.token

    override suspend fun baseUrl(): String? = current()?.baseUrl

    override suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[syncBaseUrlStoreKey] = baseUrl.trim().trimEnd('/')
            prefs[syncTokenStoreKey] = token
            prefs[syncFamilyIdStoreKey] = familyId
            prefs[syncMemberIdStoreKey] = memberId
            prefs[syncJoinCodeStoreKey] = joinCode
        }
    }

    override suspend fun setBaseUrl(baseUrl: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[syncBaseUrlStoreKey] = baseUrl.trim().trimEnd('/')
        }
    }

    override suspend fun clear() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(syncTokenStoreKey)
            prefs.remove(syncFamilyIdStoreKey)
            prefs.remove(syncMemberIdStoreKey)
            prefs.remove(syncJoinCodeStoreKey)
        }
    }
}
