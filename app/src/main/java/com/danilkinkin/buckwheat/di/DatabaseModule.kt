package com.danilkinkin.buckwheat.di

import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.danilkinkin.buckwheat.data.dao.CategoryDao
import com.danilkinkin.buckwheat.data.dao.PeriodDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.Category
import com.danilkinkin.buckwheat.data.entities.Period
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.Storage
import com.danilkinkin.buckwheat.data.entities.TagCategory
import com.danilkinkin.buckwheat.data.entities.Transaction


class AutoMigration1to2 : AutoMigrationSpec

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "Spent",
        columnName = "deleted"
    )
)
class AutoMigration2to3 : AutoMigrationSpec

// Preparing for remove storage table
class AutoMigration3to4 : AutoMigrationSpec

class AutoMigration5to6 : AutoMigrationSpec
class AutoMigration6to7 : AutoMigrationSpec
class AutoMigration7to8 : AutoMigrationSpec
class AutoMigration8to9 : AutoMigrationSpec
class AutoMigration9to10 : AutoMigrationSpec

// Rename Spent to Transaction
val AutoMigration4to5: Migration = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create the new "transactions" table
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions` " +
                    "(`type` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "`date` INTEGER NOT NULL, " +
                    "`comment` TEXT NOT NULL DEFAULT '', " +
                    "`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)"
        )

        // Copy data from the old "Spent" table to the new "transactions" table
        database.execSQL(
            "INSERT INTO `transactions` (`type`, `value`, `date`, `comment`) " +
                    "SELECT 'SPENT', `value`, `date`, `comment` FROM `Spent`"
        )

        // Drop the old "Spent" table
        database.execSQL("DROP TABLE IF EXISTS `Spent`")
    }
}

@Database(
    entities = [Transaction::class, Storage::class, RecurringTemplate::class, Period::class, Category::class, TagCategory::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AutoMigration1to2::class),
        AutoMigration(from = 2, to = 3, spec = AutoMigration2to3::class),
        AutoMigration(from = 3, to = 4, spec = AutoMigration3to4::class),
        AutoMigration(from = 5, to = 6, spec = AutoMigration5to6::class),
        AutoMigration(from = 6, to = 7, spec = AutoMigration6to7::class),
        AutoMigration(from = 7, to = 8, spec = AutoMigration7to8::class),
        AutoMigration(from = 8, to = 9, spec = AutoMigration8to9::class),
        AutoMigration(from = 9, to = 10, spec = AutoMigration9to10::class),
    ],
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class DatabaseModule : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun recurringDao(): RecurringDao

    abstract fun periodDao(): PeriodDao

    abstract fun categoryDao(): CategoryDao

    companion object {
        val MANUAL_MIGRATIONS = arrayOf<Migration>(AutoMigration4to5)
    }
}
