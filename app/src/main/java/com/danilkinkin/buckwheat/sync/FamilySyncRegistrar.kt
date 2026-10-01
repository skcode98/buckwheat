package com.danilkinkin.buckwheat.sync

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

    suspend fun invite(): MintedInvite? {
        val session = sessionStore.current() ?: return null
        return familyApiFactory.create(session.baseUrl).mintInvite(session.token)
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

    suspend fun signOut() {
        sessionStore.clear()
        membersCache.clear()
    }

    private suspend fun persist(baseUrl: String, credentials: FamilyCredentials): FamilySession {
        sessionStore.save(
            baseUrl = baseUrl,
            token = credentials.token,
            familyId = credentials.familyId,
            memberId = credentials.memberId,
        )
        return FamilySession(
            baseUrl = baseUrl,
            token = credentials.token,
            familyId = credentials.familyId,
            memberId = credentials.memberId,
        )
    }
}

fun interface FamilyApiFactory {
    fun create(baseUrl: String): FamilyApi
}
