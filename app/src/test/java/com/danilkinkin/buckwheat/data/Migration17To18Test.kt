package com.danilkinkin.buckwheat.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.di.Migration17to18
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration17To18Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DatabaseModule::class.java,
    )

    private fun seedV17(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO `saved_tags` (`id`, `name`) VALUES ('tag-1', 'holiday')")
        db.execSQL("INSERT INTO `saved_categories` (`id`, `name`, `emoji`) VALUES ('cat-1', 'Food', 'x')")
    }

    private fun migrate(): SupportSQLiteDatabase {
        val db = helper.createDatabase(TEST_DB, 17)
        seedV17(db)
        Migration17to18.migrate(db)
        return db
    }

    @Test
    fun migratesWithValidSchema() {
        helper.createDatabase(TEST_DB, 17).use { seedV17(it) }
        val validated = helper.runMigrationsAndValidate(TEST_DB, 18, true, Migration17to18)
        helper.closeWhenFinished(validated)
    }

    @Test
    fun createsAnEmptyQueueTable() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT COUNT(*) FROM `pending_mutations`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun preservesExistingRows() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.query("SELECT `name` FROM `saved_tags`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("holiday", c.getString(0))
        }
        db.query("SELECT `name`, `emoji` FROM `saved_categories`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Food", c.getString(0))
            assertEquals("x", c.getString(1))
        }
    }

    @Test
    fun storesAndReadsBackAQueuedMutation() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.execSQL(insert(false))

        db.query("SELECT `table_name`, `record_id`, `queued_at`, `is_delete` FROM `pending_mutations`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("transactions", c.getString(0))
            assertEquals("rec-1", c.getString(1))
            assertEquals(1700L, c.getLong(2))
            assertEquals(0, c.getInt(3))
            assertEquals(false, c.moveToNext())
        }
    }

    @Test
    fun aQueuedMutationCanBeMarkedAsADelete() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.execSQL(insert(false))
        db.execSQL("UPDATE `pending_mutations` SET `is_delete` = 1, `queued_at` = 2000 WHERE `record_id` = 'rec-1'")

        db.query("SELECT `is_delete`, `queued_at` FROM `pending_mutations`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(2000L, c.getLong(1))
        }
    }

    @Test
    fun theQueueKeyIsTablePlusRecord() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.execSQL("INSERT INTO `pending_mutations` (`table_name`, `record_id`, `queued_at`, `is_delete`) VALUES ('transactions', 'shared-id', 1, 0)")
        db.execSQL("INSERT INTO `pending_mutations` (`table_name`, `record_id`, `queued_at`, `is_delete`) VALUES ('saved_tags', 'shared-id', 2, 0)")

        db.query("SELECT COUNT(*) FROM `pending_mutations`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }

    @Test
    fun rejectsTheSameMutationQueuedTwice() {
        val db = migrate()
        helper.closeWhenFinished(db)

        db.execSQL(insert(false))
        val failed = try {
            db.execSQL(insert(false, 2))
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected the composite primary key to reject the duplicate", failed)
    }

    @Test
    fun requiresATableName() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val failed = try {
            db.execSQL("INSERT INTO `pending_mutations` (`record_id`, `queued_at`, `is_delete`) VALUES ('rec-1', 1, 0)")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected a null table_name to be rejected", failed)
    }

    @Test
    fun requiresARecordId() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val failed = try {
            db.execSQL("INSERT INTO `pending_mutations` (`table_name`, `queued_at`, `is_delete`) VALUES ('transactions', 1, 0)")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected a null record_id to be rejected", failed)
    }

    @Test
    fun requiresAQueuedAt() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val failed = try {
            db.execSQL("INSERT INTO `pending_mutations` (`table_name`, `record_id`, `is_delete`) VALUES ('transactions', 'rec-1', 0)")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected a null queued_at to be rejected", failed)
    }

    @Test
    fun requiresADeleteFlag() {
        val db = migrate()
        helper.closeWhenFinished(db)

        val failed = try {
            db.execSQL("INSERT INTO `pending_mutations` (`table_name`, `record_id`, `queued_at`) VALUES ('transactions', 'rec-1', 1)")
            false
        } catch (e: Exception) {
            true
        }
        assertTrue("expected a null is_delete to be rejected", failed)
    }

    private fun insert(isDelete: Boolean, queuedAt: Long = 1700L) =
        "INSERT INTO `pending_mutations` (`table_name`, `record_id`, `queued_at`, `is_delete`) " +
            "VALUES ('transactions', 'rec-1', $queuedAt, ${if (isDelete) 1 else 0})"

    private companion object {
        const val TEST_DB = "migration-17-18-test"
    }
}
