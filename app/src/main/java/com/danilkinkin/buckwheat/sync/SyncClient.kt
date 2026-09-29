package com.danilkinkin.buckwheat.sync

interface SyncClient {
    suspend fun sync(token: String, request: SyncRequest): SyncResponse
}
