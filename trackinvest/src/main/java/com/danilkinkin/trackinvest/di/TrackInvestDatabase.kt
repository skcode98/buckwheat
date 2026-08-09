/*
 * Copyright 2022, Danil Zakhvatkin (Danilkinkin), All rights reserved.
 */

package com.danilkinkin.trackinvest.di

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.danilkinkin.trackinvest.data.dao.AllocTargetDao
import com.danilkinkin.trackinvest.data.dao.CategoryDao
import com.danilkinkin.trackinvest.data.dao.CategoryDetailDao
import com.danilkinkin.trackinvest.data.dao.GoalDao
import com.danilkinkin.trackinvest.data.dao.InvestmentDao
import com.danilkinkin.trackinvest.data.dao.MarketValueDao
import com.danilkinkin.trackinvest.data.dao.MilestoneDao
import com.danilkinkin.trackinvest.data.dao.RecurringSipDao
import com.danilkinkin.trackinvest.data.dao.TemplateDao
import com.danilkinkin.trackinvest.data.entities.AllocTarget
import com.danilkinkin.trackinvest.data.entities.Category
import com.danilkinkin.trackinvest.data.entities.CategoryDetail
import com.danilkinkin.trackinvest.data.entities.Goal
import com.danilkinkin.trackinvest.data.entities.Investment
import com.danilkinkin.trackinvest.data.entities.MarketValue
import com.danilkinkin.trackinvest.data.entities.Milestone
import com.danilkinkin.trackinvest.data.entities.RecurringSip
import com.danilkinkin.trackinvest.data.entities.Template

@Database(
    entities = [
        Investment::class,
        Goal::class,
        RecurringSip::class,
        Template::class,
        Category::class,
        CategoryDetail::class,
        AllocTarget::class,
        MarketValue::class,
        Milestone::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(TrackInvestConverters::class)
abstract class TrackInvestDatabase : RoomDatabase() {
    abstract fun investmentDao(): InvestmentDao

    abstract fun goalDao(): GoalDao

    abstract fun recurringSipDao(): RecurringSipDao

    abstract fun templateDao(): TemplateDao

    abstract fun categoryDao(): CategoryDao

    abstract fun categoryDetailDao(): CategoryDetailDao

    abstract fun allocTargetDao(): AllocTargetDao

    abstract fun marketValueDao(): MarketValueDao

    abstract fun milestoneDao(): MilestoneDao
}
