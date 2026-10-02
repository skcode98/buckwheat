package com.danilkinkin.buckwheat.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.di.Migration19to20
import com.danilkinkin.buckwheat.di.Migration20to21
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 20 to 21 adds the family pool tables and the spend bucket, so it is the migration the whole family
 * budget feature rests on. A failure here is not cosmetic: `family_state` holds the pool and
 * `period_limits` holds the split, and a silently wrong schema makes a member's remaining budget
 * wrong rather than making the app fail to start.
 *
 * Built the same way as `Migration17To18Test`: create at the old version, seed, migrate by hand, and
 * validate once in `migratesWithValidSchema`. Creating directly at 21 would need an exported schema
 * asset that does not exist until the first successful build after the version bump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration20To21Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DatabaseModule::class.java,
    )

    private fun seedV20(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `transactions` (`id`, `type`, `value`, `date`, `comment`) VALUES ('t1', 'SPENT', '10.00', 1000, 'rent')"
        )
    }

    private fun migrate(): SupportSQLiteDatabase {
        val db = helper.createDatabase(TEST_DB, 20)
        seedV20(db)
        Migration20to21.migrate(db)
        return db
    }

    @Test
    fun migratesWithValidSchema() {
        helper.createDatabase(TEST_DB, 20).use { seedV20(it) }
        val validated = helper.runMigrationsAndValidate(TEST_DB, 21, true, Migration20to21)
        helper.closeWhenFinished(validated)
    }

    @Test
    fun existingSpendsBecomeMemberSpendsAndKeepTheirPayload() {
        migrate().use { db ->
            db.query("SELECT `bucket`, `comment`, `value` FROM `transactions` WHERE `id` = 't1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MEMBER", cursor.getString(0))
                assertEquals("rent", cursor.getString(1))
                assertEquals("10.00", cursor.getString(2))
            }
        }
    }

    @Test
    fun archivedSpendsBecomeMemberSpendsToo() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            seedV20(db)
            db.execSQL(
                "INSERT INTO `budget_periods` (`id`, `budget`, `start_date`, `finish_date`, `actual_finish_date`, `currency_code`, `total_spent`) VALUES ('p1', '100.00', 0, 1000, 1000, 'INR', '5.00')"
            )
            db.execSQL(
                "INSERT INTO `archived_transactions` (`id`, `period_id`, `type`, `value`, `date`, `comment`) VALUES ('a1', 'p1', 'SPENT', '5.00', 1000, 'old')"
            )
            Migration20to21.migrate(db)

            db.query("SELECT `bucket` FROM `archived_transactions` WHERE `id` = 'a1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MEMBER", cursor.getString(0))
            }
        }
    }

    @Test
    fun aHouseholdRowKeepsItsMemberSlotEmpty() {
        migrate().use { db ->
            db.execSQL(
                "INSERT INTO `transactions` (`id`, `type`, `value`, `date`, `bucket`, `member_id`) VALUES ('h1', 'SPENT', '2500.00', 1000, 'HOUSEHOLD', NULL)"
            )

            db.query("SELECT `bucket`, `member_id` FROM `transactions` WHERE `id` = 'h1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("HOUSEHOLD", cursor.getString(0))
                assertTrue("a household row must not carry a member", cursor.isNull(1))
            }
        }
    }

    @Test
    fun allocationsAreUniquePerMemberPerPeriodButNotAcrossPeriods() {
        migrate().use { db ->
            db.execSQL(
                "INSERT INTO `period_limits` (`id`, `period_id`, `member_id`, `limit_value`) VALUES ('l1', 'pool_1', 'm1', '100.00')"
            )
            db.execSQL(
                "INSERT INTO `period_limits` (`id`, `period_id`, `member_id`, `limit_value`) VALUES ('l2', 'pool_2', 'm1', '100.00')"
            )

            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO `period_limits` (`id`, `period_id`, `member_id`, `limit_value`) VALUES ('l3', 'pool_1', 'm1', '100.00')"
                )
            }
            assertTrue(
                "a member cannot hold two slices of the same period",
                duplicate.isFailure,
            )
        }
    }

    @Test
    fun aPoolRowRoundTripsItsAmountsAndPolicy() {
        migrate().use { db ->
            db.execSQL(
                """
                INSERT INTO `family_state` (
                    `family_id`, `budget`, `household_tier`, `start_date`, `finish_date`, `currency`,
                    `household_detail_visible_to_all`, `common_split_rule`, `tags_visible_to_self`,
                    `family_ai_enabled`
                ) VALUES ('f1', '30000.00', '10000.00', 0, 30000, 'INR', 0, 'EQUAL', 1, 1)
                """.trimIndent()
            )

            db.query(
                "SELECT `budget`, `household_tier`, `common_split_rule`, `tags_visible_to_self` FROM `family_state` WHERE `family_id` = 'f1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("30000.00", cursor.getString(0))
                assertEquals("10000.00", cursor.getString(1))
                assertEquals("EQUAL", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
        }
    }

    /**
 * Runs 19 to 20 only. Asserting afterwards that the tables hold no rows would prove nothing, because
 * 20 to 21 recreates both of them empty; the check that matters is that the tables are *gone*, which
 * is what a failure to select from them reports.
 */
@Test
fun migration19to20DropsTheTablesThatWereNeverWrittenTo() {
        helper.createDatabase(TEST_DB, 19).use { db ->
            Migration19to20.migrate(db)

            listOf("family_state", "period_limits").forEach { table ->
                val absent = runCatching { db.query("SELECT * FROM `$table`").use { it.count } }
                    .isFailure
                assertTrue("$table must not survive 19 to 20", absent)
            }

            db.query("SELECT `id` FROM `transactions`").use { cursor ->
                assertTrue("a migration must not disturb unrelated tables", cursor.columnCount > 0)
            }
        }
    }

    companion object {
        const val TEST_DB = "migration-20-21-test"
    }
}