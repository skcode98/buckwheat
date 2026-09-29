package com.danilkinkin.buckwheat.di

import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.danilkinkin.buckwheat.data.dao.BudgetPeriodDao
import com.danilkinkin.buckwheat.data.dao.PendingMutationDao
import com.danilkinkin.buckwheat.data.dao.RecurringDao
import com.danilkinkin.buckwheat.data.dao.SavedCategoryDao
import com.danilkinkin.buckwheat.data.dao.SavedTagDao
import com.danilkinkin.buckwheat.data.dao.SavingsGoalDao
import com.danilkinkin.buckwheat.data.dao.TransactionDao
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.FamilyState
import com.danilkinkin.buckwheat.data.entities.Member
import com.danilkinkin.buckwheat.data.entities.PendingMutation
import com.danilkinkin.buckwheat.data.entities.PeriodLimit
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
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

// Create saved_tags table for persistent tag management
val AutoMigration5to6: Migration = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_tags` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL)"
        )
    }
}

// Create recurring_templates and savings_goals tables
class AutoMigration7to8 : AutoMigrationSpec

// Create archive tables for completed budget periods
val AutoMigration6to7: Migration = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_periods` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`budget` TEXT NOT NULL, " +
                    "`start_date` INTEGER NOT NULL, " +
                    "`finish_date` INTEGER NOT NULL, " +
                    "`actual_finish_date` INTEGER, " +
                    "`currency_code` TEXT NOT NULL DEFAULT '', " +
                    "`total_spent` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `archived_transactions` " +
                    "(`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`period_id` INTEGER NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "`date` INTEGER NOT NULL, " +
                    "`comment` TEXT NOT NULL DEFAULT '', " +
                    "FOREIGN KEY (`period_id`) REFERENCES `budget_periods`(`id`) ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_archived_transactions_period_id` " +
                    "ON `archived_transactions`(`period_id`)"
        )
    }
}

// Make saved_tags.name unique (dedupe first, since legacy data may contain duplicates)
val AutoMigration8to9: Migration = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "DELETE FROM `saved_tags` WHERE `id` NOT IN " +
                    "(SELECT MIN(`id`) FROM `saved_tags` GROUP BY `name`)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_tags_name` ON `saved_tags`(`name`)"
        )
    }
}

// Add is_imported to budget_periods for month buckets created from CSV imports
val AutoMigration9to10: Migration = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `budget_periods` ADD COLUMN `is_imported` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

// Add category to transactions for the AI-assigned spend categories shown in analytics
val AutoMigration10to11: Migration = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `transactions` ADD COLUMN `category` TEXT"
        )
    }
}

// Create saved_categories table for user-managed custom categories
val AutoMigration11to12: Migration = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_categories` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_categories_name` " +
                    "ON `saved_categories`(`name`)"
        )
    }
}

// Add emoji to saved_categories so custom categories can carry a user-picked emoji
val AutoMigration12to13: Migration = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `saved_categories` ADD COLUMN `emoji` TEXT NOT NULL DEFAULT ''"
        )
    }
}

// Add category to archived_transactions so archived (historical) spends carry the
// offline/AI-assigned spend category shown in the past-period analytics view
val AutoMigration13to14: Migration = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `archived_transactions` ADD COLUMN `category` TEXT"
        )
    }
}

// Drop legacy storage table (migrated to DataStore long ago)
val AutoMigration14to15: Migration = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS `storage`")
    }
}

// Add composite index on (type, date) for the hot query path in periodCategoryTotal
val AutoMigration15to16: Migration = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_type_date` ON `transactions`(`type`, `date`)"
        )
    }
}

val Migration16to17: Migration = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = OFF")
        database.execSQL("PRAGMA legacy_alter_table = ON")

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `_sync_id_map` (`table_name` TEXT NOT NULL, `old_id` INTEGER NOT NULL, `new_id` TEXT NOT NULL, PRIMARY KEY(`table_name`, `old_id`))"
        )

        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'transactions', `uid`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `transactions`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'archived_transactions', `uid`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `archived_transactions`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'budget_periods', `id`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `budget_periods`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'saved_tags', `id`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `saved_tags`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'saved_categories', `id`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `saved_categories`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'recurring_templates', `id`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `recurring_templates`"
        )
        database.execSQL(
            "INSERT INTO `_sync_id_map` (`table_name`, `old_id`, `new_id`) SELECT 'savings_goals', `id`, lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) FROM `savings_goals`"
        )

        database.execSQL("ALTER TABLE `archived_transactions` RENAME TO `_old_archived_transactions`")
        database.execSQL("ALTER TABLE `transactions` RENAME TO `_old_transactions`")
        database.execSQL("ALTER TABLE `budget_periods` RENAME TO `_old_budget_periods`")
        database.execSQL("ALTER TABLE `saved_tags` RENAME TO `_old_saved_tags`")
        database.execSQL("ALTER TABLE `saved_categories` RENAME TO `_old_saved_categories`")
        database.execSQL("ALTER TABLE `recurring_templates` RENAME TO `_old_recurring_templates`")
        database.execSQL("ALTER TABLE `savings_goals` RENAME TO `_old_savings_goals`")

        database.execSQL("DROP INDEX IF EXISTS `index_transactions_type_date`")
        database.execSQL("DROP INDEX IF EXISTS `index_transactions_family_id`")
        database.execSQL("DROP INDEX IF EXISTS `index_saved_tags_name`")
        database.execSQL("DROP INDEX IF EXISTS `index_saved_tags_family_id`")
        database.execSQL("DROP INDEX IF EXISTS `index_saved_categories_name`")
        database.execSQL("DROP INDEX IF EXISTS `index_saved_categories_family_id`")
        database.execSQL("DROP INDEX IF EXISTS `index_archived_transactions_period_id`")
        database.execSQL("DROP INDEX IF EXISTS `index_archived_transactions_family_id`")

        database.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `date` INTEGER NOT NULL, `comment` TEXT NOT NULL DEFAULT '', `category` TEXT, `member_id` TEXT, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE TABLE IF NOT EXISTS `saved_tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_tags_name` ON `saved_tags` (`name`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_tags_family_id` ON `saved_tags` (`family_id`)")
        database.execSQL("CREATE TABLE IF NOT EXISTS `saved_categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL DEFAULT '', `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_categories_name` ON `saved_categories` (`name`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_categories_family_id` ON `saved_categories` (`family_id`)")
        database.execSQL("CREATE TABLE IF NOT EXISTS `budget_periods` (`id` TEXT NOT NULL, `budget` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `finish_date` INTEGER NOT NULL, `actual_finish_date` INTEGER, `currency_code` TEXT NOT NULL, `total_spent` TEXT NOT NULL, `is_imported` INTEGER NOT NULL DEFAULT 0, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE TABLE IF NOT EXISTS `archived_transactions` (`id` TEXT NOT NULL, `period_id` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `date` INTEGER NOT NULL, `comment` TEXT NOT NULL, `category` TEXT, `member_id` TEXT, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`), FOREIGN KEY(`period_id`) REFERENCES `budget_periods`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_archived_transactions_period_id` ON `archived_transactions` (`period_id`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_archived_transactions_family_id` ON `archived_transactions` (`family_id`)")
        database.execSQL("CREATE TABLE IF NOT EXISTS `recurring_templates` (`id` TEXT NOT NULL, `amount` TEXT NOT NULL, `comment` TEXT NOT NULL, `day_of_month` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `target_amount` TEXT NOT NULL, `current_amount` TEXT NOT NULL, `deadline` INTEGER, `created_at` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `family_id` TEXT, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_type_date` ON `transactions` (`type`, `date`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_family_id` ON `transactions` (`family_id`)")

        database.execSQL(
            "INSERT INTO `transactions` (`id`, `type`, `value`, `date`, `comment`, `category`) SELECT `m`.`new_id`, `t`.`type`, `t`.`value`, `t`.`date`, `t`.`comment`, `t`.`category` FROM `_old_transactions` `t` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'transactions' AND `m`.`old_id` = `t`.`uid`"
        )
        database.execSQL(
            "INSERT INTO `archived_transactions` (`id`, `period_id`, `type`, `value`, `date`, `comment`, `category`) SELECT `m`.`new_id`, `p`.`new_id`, `a`.`type`, `a`.`value`, `a`.`date`, `a`.`comment`, `a`.`category` FROM `_old_archived_transactions` `a` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'archived_transactions' AND `m`.`old_id` = `a`.`uid` JOIN `_sync_id_map` `p` ON `p`.`table_name` = 'budget_periods' AND `p`.`old_id` = `a`.`period_id`"
        )
        database.execSQL(
            "INSERT INTO `budget_periods` (`id`, `budget`, `start_date`, `finish_date`, `actual_finish_date`, `currency_code`, `total_spent`, `is_imported`) SELECT `m`.`new_id`, `b`.`budget`, `b`.`start_date`, `b`.`finish_date`, `b`.`actual_finish_date`, `b`.`currency_code`, `b`.`total_spent`, `b`.`is_imported` FROM `_old_budget_periods` `b` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'budget_periods' AND `m`.`old_id` = `b`.`id`"
        )
        database.execSQL(
            "INSERT INTO `saved_tags` (`id`, `name`) SELECT `m`.`new_id`, `s`.`name` FROM `_old_saved_tags` `s` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'saved_tags' AND `m`.`old_id` = `s`.`id`"
        )
        database.execSQL(
            "INSERT INTO `saved_categories` (`id`, `name`, `emoji`) SELECT `m`.`new_id`, `c`.`name`, `c`.`emoji` FROM `_old_saved_categories` `c` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'saved_categories' AND `m`.`old_id` = `c`.`id`"
        )
        database.execSQL(
            "INSERT INTO `recurring_templates` (`id`, `amount`, `comment`, `day_of_month`, `enabled`) SELECT `m`.`new_id`, `r`.`amount`, `r`.`comment`, `r`.`day_of_month`, `r`.`enabled` FROM `_old_recurring_templates` `r` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'recurring_templates' AND `m`.`old_id` = `r`.`id`"
        )
        database.execSQL(
            "INSERT INTO `savings_goals` (`id`, `name`, `target_amount`, `current_amount`, `deadline`, `created_at`, `completed`) SELECT `m`.`new_id`, `g`.`name`, `g`.`target_amount`, `g`.`current_amount`, `g`.`deadline`, `g`.`created_at`, `g`.`completed` FROM `_old_savings_goals` `g` JOIN `_sync_id_map` `m` ON `m`.`table_name` = 'savings_goals' AND `m`.`old_id` = `g`.`id`"
        )

        database.execSQL("CREATE TABLE IF NOT EXISTS `members` (`id` TEXT NOT NULL, `family_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `is_owner` INTEGER NOT NULL DEFAULT 0, `joined_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_members_family_id` ON `members` (`family_id`)")
        database.execSQL("CREATE TABLE IF NOT EXISTS `family_state` (`family_id` TEXT NOT NULL, `budget` TEXT NOT NULL, `start_date` INTEGER NOT NULL, `finish_date` INTEGER NOT NULL, `currency` TEXT NOT NULL, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`family_id`))")
        database.execSQL("CREATE TABLE IF NOT EXISTS `period_limits` (`id` TEXT NOT NULL, `period_id` TEXT NOT NULL, `member_id` TEXT NOT NULL, `family_id` TEXT, `limit_value` TEXT NOT NULL, `sync_seq` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `version` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_period_limits_period_id_member_id` ON `period_limits` (`period_id`, `member_id`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_period_limits_family_id` ON `period_limits` (`family_id`)")

        database.execSQL("DROP TABLE `_old_archived_transactions`")
        database.execSQL("DROP TABLE `_old_transactions`")
        database.execSQL("DROP TABLE `_old_budget_periods`")
        database.execSQL("DROP TABLE `_old_saved_tags`")
        database.execSQL("DROP TABLE `_old_saved_categories`")
        database.execSQL("DROP TABLE `_old_recurring_templates`")
        database.execSQL("DROP TABLE `_old_savings_goals`")
        database.execSQL("DROP TABLE `_sync_id_map`")

        database.execSQL("PRAGMA legacy_alter_table = OFF")
        database.execSQL("PRAGMA foreign_keys = ON")
    }
}

val Migration17to18: Migration = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_mutations` (`table_name` TEXT NOT NULL, `record_id` TEXT NOT NULL, `queued_at` INTEGER NOT NULL, `is_delete` INTEGER NOT NULL, PRIMARY KEY(`table_name`, `record_id`))"
        )
    }
}

@Database(
    entities = [Transaction::class, SavedTag::class, SavedCategory::class, BudgetPeriod::class, ArchivedTransaction::class, RecurringTemplate::class, SavingsGoal::class, Member::class, FamilyState::class, PeriodLimit::class, PendingMutation::class],
    version = 18,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AutoMigration1to2::class),
        AutoMigration(from = 2, to = 3, spec = AutoMigration2to3::class),
        AutoMigration(from = 3, to = 4, spec = AutoMigration3to4::class),
        AutoMigration(from = 7, to = 8, spec = AutoMigration7to8::class),
    ],
    exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class DatabaseModule : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun savedTagDao(): SavedTagDao

    abstract fun savedCategoryDao(): SavedCategoryDao

    abstract fun budgetPeriodDao(): BudgetPeriodDao

    abstract fun recurringDao(): RecurringDao

    abstract fun savingsGoalDao(): SavingsGoalDao

    abstract fun pendingMutationDao(): PendingMutationDao

    companion object {
        val MANUAL_MIGRATIONS = arrayOf<Migration>(AutoMigration4to5, AutoMigration5to6, AutoMigration6to7, AutoMigration8to9, AutoMigration9to10, AutoMigration10to11, AutoMigration11to12, AutoMigration12to13, AutoMigration13to14, AutoMigration14to15, AutoMigration15to16, Migration16to17, Migration17to18)
    }
}
