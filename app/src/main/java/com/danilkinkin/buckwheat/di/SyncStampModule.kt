package com.danilkinkin.buckwheat.di

import com.danilkinkin.buckwheat.sync.RoomSyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncDirtyClock
import com.danilkinkin.buckwheat.sync.SyncDirtyMarker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncStampBindingsModule {

    @Binds
    @Singleton
    abstract fun bindSyncDirtyMarker(marker: RoomSyncDirtyMarker): SyncDirtyMarker
}

@Module
@InstallIn(SingletonComponent::class)
object SyncStampModule {

    @Provides
    @Singleton
    fun provideSyncStampDao(db: DatabaseModule) = db.syncStampDao()

    @Provides
    @Singleton
    fun provideSyncDirtyClock(): SyncDirtyClock = SyncDirtyClock { System.currentTimeMillis() }
}