package com.danilkinkin.buckwheat.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.di.Migration16to17
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration16To17Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DatabaseModule::class.java,
    )

    private fun seedV16(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `budget_periods` (`id`, `budget`, `start_date`, `finish_date`, `currency_code`, `total_spent`) VALUES (1, '100.00', 1000, 2000, 'USD', '10.00')"
        )
        db.execSQL(
            "INSERT INTO `budget_periods` (`id`, `budget`, `start_date`, `finish_date`, `currency_code`, `total_spent`) VALUES (2, '250.50', 3000, 4000, 'USD', '0.00')"
        )
        db.execSQL(
            "INSERT INTO `transactions` (`uid`, `type`, `value`, `date`, `comment`, `category`) VALUES (10, 'expense', '5.25', 1500, 'lunch', 'food')"
        )
        db.execSQL(
            "INSERT INTO `transactions` (`uid`, `type`, `value`, `date`, `comment`) VALUES (11, 'income', '900.00', 1600, 'salary')"
        )
        db.execSQL(
            "INSERT INTO `archived_transactions` (`uid`, `period_id`, `type`, `value`, `date`, `comment`, `category`) VALUES (20, 1, 'expense', '7.75', 1200, 'old', 'food')"
        )
        db.execSQL(
            "INSERT INTO `archived_transactions` (`uid`, `period_id`, `type`, `value`, `date`, `comment`) VALUES (21, 2, 'expense', '1.00', 3200, 'older')"
        )
        db.execSQL("INSERT INTO `saved_tags` (`id`, `name`) VALUES (30, 'holiday')")
        db.execSQL("INSERT INTO `saved_categories` (`id`, `name`, `emoji`) VALUES (40, 'Food', 'x')")
        db.execSQL("INSERT INTO `saved_categories` (`id`, `name`, `emoji`) VALUES (41, 'Rent', 'y')")
        db.execSQL("INSERT INTO `recurring_templates` (`id`, `amount`, `comment`, `day_of_month`, `enabled`) VALUES (50, '99.00', 'rent', 1, 1)")
        db.execSQL("INSERT INTO `savings_goals` (`id`, `name`, `target_amount`, `current_amount`, `created_at`, `completed`) VALUES (60, 'Car', '5000.00', '100.00', 1700, 0)")
    }

    private fun migrate(): SupportSQLiteDatabase {
        val db = helper.createDatabase(TEST_DB, 16)
        seedV16(db)
        Migration16to17.migrate(db)
        return db
    }

    @Test
    fun migratesWithValidSchema() {
        helper.createDatabase(TEST_DB, 16).use { seedV16(it) }
        val validated = helper.runMigrationsAndValidate(TEST_DB, 17, true, Migration16to17)
        helper.closeWhenFinished(validated)
    }

    @Test
    fun preservesBudgetPeriods() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT COUNT(*) FROM `budget_periods`").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        val rows = mutableListOf<String>()
        db.query("SELECT `budget` FROM `budget_periods` ORDER BY `budget`").use { c ->
            while (c.moveToNext()) rows.add(c.getString(0))
        }
        assertEquals(listOf("100.00", "250.50"), rows)
    }

    @Test
    fun convertsPrimaryKeysToUuidText() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val ids = mutableListOf<String>()
        db.query("SELECT `id` FROM `transactions`").use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        assertEquals(2, ids.size)
        for (id in ids) {
            assertTrue("expected uuid-shaped id but was '$id'", UUID_PATTERN.matches(id))
        }
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun assignsDefaultSyncMetadata() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT `family_id`, `member_id`, `sync_seq`, `updated_at`, `deleted_at`, `version` FROM `transactions`").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(0L, c.getLong(2))
            assertEquals(0L, c.getLong(3))
            assertTrue(c.isNull(4))
            assertEquals(1, c.getInt(5))
        }
    }

    @Test
    fun preservesTransactionPayload() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val rows = mutableListOf<List<String?>>()
        db.query("SELECT `type`, `value`, `date`, `comment`, `category` FROM `transactions` ORDER BY `date`").use { c ->
            while (c.moveToNext()) {
                rows.add(listOf(c.getString(0), c.getString(1), c.getLong(2).toString(), c.getString(3), c.getString(4)))
            }
        }
        assertEquals(2, rows.size)
        assertEquals(listOf("expense", "5.25", "1500", "lunch", "food"), rows[0])
        assertEquals(listOf("income", "900.00", "1600", "salary", null), rows[1])
    }

    @Test
    fun remapsArchivedTransactionPeriodForeignKey() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query(
            "SELECT COUNT(*) FROM `archived_transactions` `a` JOIN `budget_periods` `p` ON `a`.`period_id` = `p`.`id`"
        ).use { c ->
            c.moveToFirst()
            assertEquals("every archived row must resolve to a real period", 2, c.getInt(0))
        }
    }

    @Test
    fun archivedTransactionsPointAtTheirOwnPeriod() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val pairs = mutableListOf<Pair<String, String>>()
        db.query(
            "SELECT `a`.`comment`, `p`.`budget` FROM `archived_transactions` `a` JOIN `budget_periods` `p` ON `a`.`period_id` = `p`.`id` ORDER BY `a`.`date`"
        ).use { c ->
            while (c.moveToNext()) pairs.add(c.getString(0) to c.getString(1))
        }
        assertEquals(listOf("old" to "100.00", "older" to "250.50"), pairs)
    }

    @Test
    fun archivedPeriodIdIsNotTheLegacyInteger() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT `period_id` FROM `archived_transactions`").use { c ->
            while (c.moveToNext()) {
                val periodId = c.getString(0)
                assertTrue("expected uuid period id but was '$periodId'", UUID_PATTERN.matches(periodId))
            }
        }
    }

    @Test
    fun createsSyncOnlyTables() {
        val db = migrate()
        helper.closeWhenFinished(db)

        listOf("members", "family_state", "period_limits").forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                c.moveToFirst()
                assertEquals("$table should start empty", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun preservesTagsCategoriesRecurringAndGoals() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT `name` FROM `saved_tags`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("holiday", c.getString(0))
        }
        db.query("SELECT `name`, `emoji` FROM `saved_categories` ORDER BY `name`").use { c ->
            val rows = mutableListOf<String>()
            while (c.moveToNext()) rows.add("${c.getString(0)}${c.getString(1)}")
            assertEquals(listOf("Foodx", "Renty"), rows)
        }
        db.query("SELECT `amount`, `day_of_month`, `enabled` FROM `recurring_templates`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("99.00", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(1, c.getInt(2))
        }
        db.query("SELECT `name`, `target_amount`, `current_amount` FROM `savings_goals`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Car", c.getString(0))
            assertEquals("5000.00", c.getString(1))
            assertEquals("100.00", c.getString(2))
        }
    }

    @Test
    fun uniqueIndexesStillEnforced() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val failed = try {
            db.execSQL("INSERT INTO `saved_categories` (`id`, `name`, `emoji`) VALUES ('dup', 'Food', 'z')")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected unique name index to reject the duplicate", failed)
    }

    private companion object {
        const val TEST_DB = "migration-16-17-test"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
