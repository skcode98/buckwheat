package com.danilkinkin.buckwheat.sync

import android.util.Log
import javax.inject.Inject

class FamilySyncRegistrar @Inject constructor(
    private val sessionStore: FamilySessionStore,
    private val familyApiFactory: FamilyApiFactory,
    private val membersCache: FamilyMembersCache,
) {
    suspend fun enrol(baseUrl: String, displayName: String): FamilySession {
        val base = baseUrl.trim().trimEnd('/')
        val credentials = familyApiFactory.create(base).createFamily(displayName)
        return persist(base, credentials)
    }

    suspend fun join(baseUrl: String, code: String, displayName: String): FamilySession {
        val base = baseUrl.trim().trimEnd('/')
        val credentials = familyApiFactory.create(base).joinFamily(code, displayName)
        return persist(base, credentials)
    }

    suspend fun whoami(): WhoAmI? {
        val session = sessionStore.current() ?: return null
        return familyApiFactory.create(session.baseUrl).whoami(session.token)
    }

    /**
     * The roster, or null when there is no session. A failed refresh falls back to the cached roster
     * instead of propagating, IOException or not: a stale name is better than an empty screen, because
     * the roster is the only thing that can attribute an already synced transaction.
     */
    suspend fun members(): List<FamilyMember>? {
        val session = sessionStore.current() ?: return null
        return try {
            val fetched = familyApiFactory.create(session.baseUrl).members(session.token)
            membersCache.replaceMembers(fetched)
            fetched
        } catch (e: Exception) {
            membersCache.readMembers()
        }
    }

    /** Fills the roster cache for a session that is not in the store yet. */
    private suspend fun fetchMembers(baseUrl: String, token: String) {
        membersCache.replaceMembers(familyApiFactory.create(baseUrl).members(token))
    }

    suspend fun signOut() {
        sessionStore.clear()
        membersCache.clear()
    }

    private suspend fun persist(baseUrl: String, credentials: FamilyCredentials): FamilySession {
        membersCache.clear()
        sessionStore.save(
            baseUrl = baseUrl,
            token = credentials.token,
            familyId = credentials.familyId,
            memberId = credentials.memberId,
            joinCode = credentials.joinCode,
        )
        // Fetch the roster straight away rather than waiting for the caller to notice it is empty.
        //
        // Clearing the cache is required -- the previous family's names must not survive -- but that
        // leaves every family screen showing a roster of nobody until something happens to refresh it.
        // Enrolling is exactly the moment somebody is about to open the budget sheet, so the fetch
        // belongs here. A failure is swallowed on purpose: the roster is a cache, and being unable to
        // reach the server at this moment must not fail the enrolment that succeeded.
        runCatching { fetchMembers(baseUrl, credentials.token) }
            .onFailure { Log.d("FamilySync", "roster fetch failed after enrolment: ${it.message}") }
        return FamilySession(
            baseUrl = baseUrl,
            token = credentials.token,
            familyId = credentials.familyId,
            memberId = credentials.memberId,
            joinCode = credentials.joinCode,
        )
    }
}

fun interface FamilyApiFactory {
    fun create(baseUrl: String): FamilyApi
}
