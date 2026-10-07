package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.sync.FamilySession
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSessionStore(
    initial: FamilySession? = null,
) : FamilySessionStore {
    private val state = MutableStateFlow(initial)

    var session: FamilySession?
        get() = state.value
        private set(value) {
            state.value = value
        }

    val saved = mutableListOf<FamilySession>()
    var cleared = false

    override fun session(): Flow<FamilySession?> = state

    override suspend fun current(): FamilySession? = state.value

    override suspend fun token(): String? = state.value?.token

    override suspend fun baseUrl(): String? = state.value?.baseUrl

    override suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String) {
        val stored = FamilySession(baseUrl, token, familyId, memberId, joinCode)
        saved.add(stored)
        state.value = stored
    }

    override suspend fun setBaseUrl(baseUrl: String) {
        state.value = state.value?.copy(baseUrl = baseUrl)
    }

    override suspend fun clear() {
        cleared = true
        state.value = null
    }
}
