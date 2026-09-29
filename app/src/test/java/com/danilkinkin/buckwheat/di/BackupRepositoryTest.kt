package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.backup.parseBackupData
import com.danilkinkin.buckwheat.budgetDataStore
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRepositoryTest {

    lateinit var backupRepository: BackupRepository
    lateinit var database: DatabaseModule
    lateinit var transactionDao: FakeTransactionDao
    lateinit var savedTagDao: FakeSavedTagDao
    lateinit var savedCategoryDao: FakeSavedCategoryDao
    lateinit var budgetPeriodDao: FakeBudgetPeriodDao
    lateinit var recurringDao: FakeRecurringDao
    lateinit var savingsGoalDao: FakeSavingsGoalDao

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        transactionDao = FakeTransactionDao()
        savedTagDao = FakeSavedTagDao()
        savedCategoryDao = FakeSavedCategoryDao()
        budgetPeriodDao = FakeBudgetPeriodDao()
        recurringDao = FakeRecurringDao()
        savingsGoalDao = FakeSavingsGoalDao()
        database = Room.inMemoryDatabaseBuilder(context, DatabaseModule::class.java)
            .allowMainThreadQueries()
            .build()
        backupRepository = BackupRepository(
            context = context,
            database = database,
            transactionDao = transactionDao,
            savedTagDao = savedTagDao,
            savedCategoryDao = savedCategoryDao,
            budgetPeriodDao = budgetPeriodDao,
            recurringDao = recurringDao,
            savingsGoalDao = savingsGoalDao,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exportThenRestorePreservesAllData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Date(1_700_000_000_000L)

        val spend = Transaction(
            id = "7",
            type = TransactionType.SPENT,
            value = BigDecimal("150.50"),
            date = Date(now.time + 1000),
            comment = "lunch",
            category = "FOOD",
        )
        transactionDao.insert(spend)

        val period = BudgetPeriod(
            id = "3",
            budget = BigDecimal("1000.00"),
            startDate = now,
            finishDate = Date(now.time + 86_400_000L),
            actualFinishDate = null,
            currencyCode = "INR",
            totalSpent = BigDecimal("150.50"),
            isImported = false,
        )
        budgetPeriodDao.insert(period)
        budgetPeriodDao.insertArchivedTransactions(
            listOf(
                ArchivedTransaction(
                    id = "9",
                    periodId = "3",
                    type = TransactionType.SPENT,
                    value = BigDecimal("20.00"),
                    date = now,
                    comment = "coffee",
                )
            )
        )

        savedTagDao.insert(SavedTag(id = "1", name = "work"))
        savedCategoryDao.insert(SavedCategory(id = "2", name = "coffee"))
        recurringDao.insert(RecurringTemplate(amount = BigDecimal("99.00"), comment = "Netflix", dayOfMonth = 5, enabled = true, id = "4"))
        savingsGoalDao.insert(
            SavingsGoal(id = "11", name = "vacation", targetAmount = BigDecimal("50000.00"), currentAmount = BigDecimal.ZERO, deadline = null, createdAt = now, completed = false)
        )

        context.budgetDataStore.edit { it[budgetStoreKey] = "500.00" }
        context.settingsDataStore.edit { it[debugStoreKey] = true }

        val json = backupRepository.exportBackup()
        assertTrue(json.contains("FOOD"))
        assertTrue(json.contains("coffee"))

        transactionDao.deleteAll()
        budgetPeriodDao.deleteAll()
        savedTagDao.deleteAll()
        savedCategoryDao.deleteAll()
        recurringDao.deleteAll()
        savingsGoalDao.deleteAll()
        context.budgetDataStore.edit { it.clear() }
        context.settingsDataStore.edit { it.clear() }

        val restored = backupRepository.restoreBackup(json)
        assertTrue(restored)

        val restoredTransactions = transactionDao.spends
        assertEquals(1, restoredTransactions.size)
        assertEquals("FOOD", restoredTransactions[0].category)
        assertEquals("7", restoredTransactions[0].id)

        val restoredPeriods = budgetPeriodDao.getAllNow()
        assertEquals(1, restoredPeriods.size)
        assertEquals("3", restoredPeriods[0].id)

        val restoredArchived = budgetPeriodDao.getAllArchivedNow()
        assertEquals(1, restoredArchived.size)
        assertEquals("3", restoredArchived[0].periodId)
        assertEquals("9", restoredArchived[0].id)

        assertEquals(1, savedTagDao.getAllNow().size)
        assertEquals(1, savedCategoryDao.getAllNow().size)
        assertEquals(1, recurringDao.getAllNow().size)
        assertEquals(1, savingsGoalDao.getAllNow().size)

        assertEquals("500.00", context.budgetDataStore.data.first()[budgetStoreKey])
        assertEquals(true, context.settingsDataStore.data.first()[debugStoreKey])
    }

    @Test
    fun restoreRejectsInvalidJson() = runTest {
        assertFalse(backupRepository.restoreBackup("garbage"))
        assertFalse(backupRepository.restoreBackup(""))
        assertFalse(backupRepository.restoreBackup("{\"app\":\"other\",\"version\":1}"))
    }

    @Test
    fun exportProducesParseableJson() = runTest {
        val json = backupRepository.exportBackup()
        val parsed = parseBackupData(json)

        assertTrue(parsed != null)
        parsed!!
        assertTrue(parsed.transactions.isEmpty())
        assertTrue(parsed.budgetPeriods.isEmpty())
    }

    @Test
    fun exportOmitsSensitiveVoiceAiApiKey() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.settingsDataStore.edit { it[voiceAiApiKeyStoreKey] = "sk-secret-123" }

        val json = backupRepository.exportBackup()

        assertFalse(json.contains("sk-secret-123"))
        assertFalse(json.contains("voiceAiApiKey"))
    }
}
