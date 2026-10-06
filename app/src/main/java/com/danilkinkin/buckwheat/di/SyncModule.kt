package com.danilkinkin.buckwheat.di

import androidx.room.withTransaction
import com.danilkinkin.buckwheat.data.dao.FamilyTransactionDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.sync.DataStoreFamilyMembersCache
import com.danilkinkin.buckwheat.sync.DataStoreFamilySessionStore
import com.danilkinkin.buckwheat.sync.DataStoreSyncStateStore
import com.danilkinkin.buckwheat.sync.FamilyApi
import com.danilkinkin.buckwheat.sync.FamilyApiFactory
import com.danilkinkin.buckwheat.sync.FamilyMembersCache
import com.danilkinkin.buckwheat.sync.FamilySessionStore
import com.danilkinkin.buckwheat.sync.HttpFamilyApi
import com.danilkinkin.buckwheat.sync.RoomSyncDatabase
import com.danilkinkin.buckwheat.sync.SessionSyncClient
import com.danilkinkin.buckwheat.sync.SyncBindings
import com.danilkinkin.buckwheat.sync.SyncClient
import com.danilkinkin.buckwheat.sync.SyncClock
import com.danilkinkin.buckwheat.sync.SyncDatabase
import com.danilkinkin.buckwheat.sync.SyncEngine
import com.danilkinkin.buckwheat.sync.SyncStateStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {
    @Binds
    @Singleton
    abstract fun bindFamilySessionStore(
        store: DataStoreFamilySessionStore,
    ): FamilySessionStore

    @Binds
    @Singleton
    abstract fun bindSyncStateStore(
        store: DataStoreSyncStateStore,
    ): SyncStateStore

    @Binds
    @Singleton
    abstract fun bindFamilyMembersCache(
        store: DataStoreFamilyMembersCache,
    ): FamilyMembersCache

    @Binds
    @Singleton
    abstract fun bindFamilyApiFactory(factory: DefaultFamilyApiFactory): FamilyApiFactory

    @Binds
    @Singleton
    abstract fun bindSyncClient(client: SessionSyncClient): SyncClient
}

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun provideSyncDatabase(
        database: DatabaseModule,
        pendingMutationDao: PendingMutationDao,
        transactionDao: TransactionDao,
        familyTransactionDao: FamilyTransactionDao,
        syncStateStore: SyncStateStore,
    ): SyncDatabase = RoomSyncDatabase(
        gateways = SyncBindings(pendingMutationDao).gateways(familyTransactionDao),
        pendingMutationDao = pendingMutationDao,
        syncStateStore = syncStateStore,
        transactionDao = transactionDao,
        familyTransactionDao = familyTransactionDao,
        runInTransaction = { block -> database.withTransaction { block() } },
    )

    @Provides
    @Singleton
    fun provideSyncClock(): SyncClock = SyncClock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideSyncEngine(
        client: SyncClient,
        database: SyncDatabase,
        store: FamilySessionStore,
        syncStateStore: SyncStateStore,
        clock: SyncClock,
        membersCache: FamilyMembersCache,
        familyApiFactory: FamilyApiFactory,
    ) = SyncEngine(
        client = client,
        database = database,
        sessionProvider = { store.current() },
        syncStateStore = syncStateStore,
        clock = clock,
        membersCache = membersCache,
        familyApiFactory = familyApiFactory,
    )
}

class DefaultFamilyApiFactory @Inject constructor() : FamilyApiFactory {
    override fun create(baseUrl: String): FamilyApi = HttpFamilyApi(baseUrl)
}
