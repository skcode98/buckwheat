package com.danilkinkin.buckwheat.sync

import java.io.IOException
import javax.inject.Inject

class SessionSyncClient @Inject constructor(
    private val sessionStore: FamilySessionStore,
) : SyncClient {
    override suspend fun sync(token: String, request: SyncRequest): SyncResponse {
        val baseUrl = sessionStore.baseUrl() ?: throw IOException("no family session")
        return HttpSyncClient(baseUrl).sync(token, request)
    }
}
