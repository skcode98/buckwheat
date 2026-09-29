package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeSessionStore(
    initial: FamilySession? = null,
) : FamilySessionStore {
    var session: FamilySession? = initial
        private set

    val saved = mutableListOf<FamilySession>()
    var cleared = false

    override fun session(): Flow<FamilySession?> = flowOf(session)

    override suspend fun current(): FamilySession? = session

    override suspend fun token(): String? = session?.token

    override suspend fun baseUrl(): String? = session?.baseUrl

    override suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String) {
        val stored = FamilySession(baseUrl, token, familyId, memberId)
        saved.add(stored)
        session = stored
    }

    override suspend fun setBaseUrl(baseUrl: String) {
        session = session?.copy(baseUrl = baseUrl)
    }

    override suspend fun clear() {
        cleared = true
        session = null
    }
}
