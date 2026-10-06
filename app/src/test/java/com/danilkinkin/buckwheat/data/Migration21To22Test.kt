package com.danilkinkin.buckwheat.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.danilkinkin.buckwheat.di.DatabaseModule
import com.danilkinkin.buckwheat.di.Migration21to22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 21 to 22 swaps the family governance tables for the family transaction ledger. The three tables
 * dropped here are dropped for good — no copy, no history — so the assertions that matter are that
 * they are unreachable afterwards and that the new `family_transactions` table comes up with the
 * three indices the sync pull path filters on.
 *
 * Built the same way as `Migration20To21Test`: create at the old version, seed, migrate by hand.
 * Validating against the exported 22 schema needs an asset that only exists after the first
 * successful build with `version = 22`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration21To22Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DatabaseModule::class.java,
    )

    @Test
    fun theFamilyTableIsCreatedWithItsIndices() {
        helper.createDatabase(TEST_DB, 21).apply {
            Migration21to22.migrate(this)
            query("SELECT * FROM `family_transactions`").use { it.moveToFirst() }
            val indices = query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'family_transactions'")
                .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertTrue(indices.contains("index_family_transactions_member_id"))
            assertTrue(indices.contains("index_family_transactions_date"))
            assertTrue(indices.contains("index_family_transactions_type"))
        }.close()
    }

    @Test
    fun theThreeGovernanceTablesAreGone() {
        helper.createDatabase(TEST_DB, 21).apply {
            Migration21to22.migrate(this)
            assertTrue(runCatching { query("SELECT * FROM `family_state`") }.isFailure)
            assertTrue(runCatching { query("SELECT * FROM `period_limits`") }.isFailure)
            assertTrue(runCatching { query("SELECT * FROM `spend_assignments`") }.isFailure)
        }.close()
    }

    @Test
    fun rowsSurviveTheMigration() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL("INSERT INTO `transactions` (`id`,`type`,`value`,`date`,`comment`,`category`,`version`) VALUES ('t1','SPENT',1.5,1700000000000,'coffee',NULL,1)")
            Migration21to22.migrate(this)
            query("SELECT comment FROM `transactions` WHERE id = 't1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("coffee", it.getString(0))
            }
        }.close()
    }

    companion object {
        const val TEST_DB = "migration-21-22-test"
    }
}
