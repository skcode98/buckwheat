/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun provideDatabase(
        @ApplicationContext app: Context,
    ) = Room.databaseBuilder(
        app.applicationContext,
        TrackInvestDatabase::class.java,
        "trackinvest-db",
    )
        .fallbackToDestructiveMigration()
        .build()

    @Singleton
    @Provides
    fun provideInvestmentDao(db: TrackInvestDatabase) = db.investmentDao()

    @Singleton
    @Provides
    fun provideGoalDao(db: TrackInvestDatabase) = db.goalDao()

    @Singleton
    @Provides
    fun provideRecurringSipDao(db: TrackInvestDatabase) = db.recurringSipDao()

    @Singleton
    @Provides
    fun provideTemplateDao(db: TrackInvestDatabase) = db.templateDao()

    @Singleton
    @Provides
    fun provideCategoryDao(db: TrackInvestDatabase) = db.categoryDao()

    @Singleton
    @Provides
    fun provideCategoryDetailDao(db: TrackInvestDatabase) = db.categoryDetailDao()

    @Singleton
    @Provides
    fun provideAllocTargetDao(db: TrackInvestDatabase) = db.allocTargetDao()

    @Singleton
    @Provides
    fun provideMarketValueDao(db: TrackInvestDatabase) = db.marketValueDao()

    @Singleton
    @Provides
    fun provideMilestoneDao(db: TrackInvestDatabase) = db.milestoneDao()
}
