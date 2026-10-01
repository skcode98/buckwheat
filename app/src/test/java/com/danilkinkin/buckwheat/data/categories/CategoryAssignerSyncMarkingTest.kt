package com.danilkinkin.buckwheat.data.categories

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.di.FakeBudgetPeriodDao
import com.danilkinkin.buckwheat.di.FakeTransactionDao
import com.danilkinkin.buckwheat.settings.FakeSyncDirtyMarker
import com.danilkinkin.buckwheat.sync.SyncTables
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

// Assigning a category is a targeted SQL UPDATE that leaves the sync columns alone, so without a
// mark the row reads as clean and an incoming pull for it silently reverts the category.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoryAssignerSyncMarkingTest {

    private lateinit var transactionDao: FakeTransactionDao
    private lateinit var budgetPeriodDao: FakeBudgetPeriodDao
    private lateinit var marker: FakeSyncDirtyMarker

    @Before
    fun setUp() {
        transactionDao = FakeTransactionDao()
        budgetPeriodDao = FakeBudgetPeriodDao()
        marker = FakeSyncDirtyMarker()
    }

    private fun assigner(): CategoryAssigner = CategoryAssigner(
        context = ApplicationProvider.getApplicationContext<Context>(),
        transactionDao = transactionDao,
        budgetPeriodDao = budgetPeriodDao,
        syncDirtyMarker = marker,
    )

    private fun spend(id: String, comment: String, category: String? = null) = Transaction(
        id = id,
        type = TransactionType.SPENT,
        value = BigDecimal("10"),
        date = Date(1_700_000_000_000L),
        comment = comment,
        category = category,
    )

    private fun archived(id: String, comment: String, category: String? = null) = ArchivedTransaction(
        id = id,
        periodId = "period-1",
        type = TransactionType.SPENT,
        value = BigDecimal("10"),
        date = Date(1_700_000_000_000L),
        comment = comment,
        category = category,
    )

    @Test
    fun `offline assignment marks every categorized active spend`() = runTest {
        transactionDao.insert(spend("tx-1", "lunch at the cafe"))
        transactionDao.insert(spend("tx-2", "bus fare to the city"))

        assigner().assignToUncategorized()

        assertEquals(listOf("tx-1", "tx-2"), marker.upserted(SyncTables.TRANSACTIONS))
        assertEquals("FOOD", transactionDao.getById("tx-1")!!.category)
        assertEquals("TRANSPORT", transactionDao.getById("tx-2")!!.category)
    }

    @Test
    fun `offline assignment marks every categorized archived spend`() = runTest {
        budgetPeriodDao.insertArchivedTransactions(
            listOf(
                archived("ar-1", "monthly rent payment"),
                archived("ar-2", "new laptop purchase"),
            )
        )

        assigner().assignToUncategorized()

        assertEquals(listOf("ar-1", "ar-2"), marker.upserted(SyncTables.ARCHIVED_TRANSACTIONS))
        assertEquals("BILLS", budgetPeriodDao.getAllArchivedNow().first { it.id == "ar-1" }.category)
        assertEquals(
            "SHOPPING",
            budgetPeriodDao.getAllArchivedNow().first { it.id == "ar-2" }.category,
        )
    }

    @Test
    fun `active and archived assignments are marked on their own tables`() = runTest {
        transactionDao.insert(spend("tx-1", "lunch at the cafe"))
        budgetPeriodDao.insertArchivedTransactions(listOf(archived("ar-1", "bus fare to the city")))

        assigner().assignToUncategorized()

        assertEquals(listOf("tx-1"), marker.upserted(SyncTables.TRANSACTIONS))
        assertEquals(listOf("ar-1"), marker.upserted(SyncTables.ARCHIVED_TRANSACTIONS))
    }

    @Test
    fun `already categorized spends are not marked again`() = runTest {
        transactionDao.insert(spend("tx-1", "lunch at the cafe", category = "SHOPPING"))
        budgetPeriodDao.insertArchivedTransactions(
            listOf(archived("ar-1", "bus fare to the city", category = "FOOD"))
        )

        assigner().assignToUncategorized()

        assertTrue(marker.upserts.isEmpty())
        assertEquals("SHOPPING", transactionDao.getById("tx-1")!!.category)
        assertEquals("FOOD", budgetPeriodDao.getAllArchivedNow().single().category)
    }

    @Test
    fun `non-spent rows are never assigned nor marked`() = runTest {
        transactionDao.insert(
            spend("income-1", "salary").copy(type = TransactionType.INCOME)
        )
        transactionDao.insert(
            spend("daily-1", "lunch at the cafe").copy(type = TransactionType.SET_DAILY_BUDGET)
        )

        assigner().assignToUncategorized()

        assertTrue(marker.upserts.isEmpty())
        assertNull(transactionDao.getById("income-1")!!.category)
        assertNull(transactionDao.getById("daily-1")!!.category)
    }

    @Test
    fun `comments the keywords cannot place are left unmarked`() = runTest {
        transactionDao.insert(spend("tx-1", "zzyzx unknown thing"))
        budgetPeriodDao.insertArchivedTransactions(listOf(archived("ar-1", "qwoiu nothing")))

        assigner().assignToUncategorized()

        assertTrue(marker.upserts.isEmpty())
        assertNull(transactionDao.getById("tx-1")!!.category)
        assertNull(budgetPeriodDao.getAllArchivedNow().single().category)
    }

    @Test
    fun `an empty database marks nothing`() = runTest {
        assigner().assignToUncategorized()

        assertTrue(marker.upserts.isEmpty())
    }

    @Test
    fun `assignment never queues a tombstone`() = runTest {
        transactionDao.insert(spend("tx-1", "lunch at the cafe").copy(familyId = "family-1", syncSeq = 3L))
        budgetPeriodDao.insertArchivedTransactions(
            listOf(archived("ar-1", "bus fare to the city").copy(familyId = "family-1", syncSeq = 4L))
        )

        assigner().assignToUncategorized()

        assertTrue(marker.deletes.isEmpty())
    }
}