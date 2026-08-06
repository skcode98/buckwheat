package com.danilkinkin.buckwheat.backup

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Date

class BackupDataTest {

    private fun emptyData() = BackupData(
        version = BACKUP_VERSION,
        exportedAt = 0L,
        transactions = emptyList(),
        budgetPeriods = emptyList(),
        archivedTransactions = emptyList(),
        savedTags = emptyList(),
        savedCategories = emptyList(),
        recurringTemplates = emptyList(),
        savingsGoals = emptyList(),
        budgetPreferences = emptyMap(),
        settingsPreferences = emptyMap(),
    )

    private fun sampleData(): BackupData {
        val now = Date(1_700_000_000_000L)
        val tx = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal("150.50"),
            date = Date(now.time + 1000),
            comment = "lunch",
            category = "FOOD",
        ).also { it.uid = 7 }

        val period = BudgetPeriod(
            budget = BigDecimal("1000.00"),
            startDate = now,
            finishDate = Date(now.time + 10 * 86_400_000L),
            actualFinishDate = null,
            currencyCode = "INR",
            totalSpent = BigDecimal("150.50"),
            isImported = false,
        ).also { it.id = 3 }

        val archived = ArchivedTransaction(
            periodId = 3,
            type = TransactionType.SPENT,
            value = BigDecimal("20.00"),
            date = now,
            comment = "coffee",
        ).also { it.uid = 9 }

        val tag = SavedTag(name = "work").also { it.id = 1 }
        val category = SavedCategory(name = "coffee", emoji = "☕").also { it.id = 2 }
        val recurring = RecurringTemplate(
            amount = BigDecimal("99.00"),
            comment = "Netflix",
            dayOfMonth = 5,
            enabled = true,
            id = 4,
        )
        val goal = SavingsGoal(
            name = "vacation",
            targetAmount = BigDecimal("50000.00"),
            currentAmount = BigDecimal("1200.50"),
            deadline = Date(now.time + 30 * 86_400_000L),
            createdAt = now,
            completed = false,
            id = 11,
        )

        return BackupData(
            version = BACKUP_VERSION,
            exportedAt = now.time,
            transactions = listOf(tx),
            budgetPeriods = listOf(period),
            archivedTransactions = listOf(archived),
            savedTags = listOf(tag),
            savedCategories = listOf(category),
            recurringTemplates = listOf(recurring),
            savingsGoals = listOf(goal),
            budgetPreferences = mapOf(
                "budget" to BackupValue.Str("1000.00"),
                "lastChangeDailyBudgetDate" to BackupValue.LongValue(now.time),
            ),
            settingsPreferences = mapOf(
                "debug" to BackupValue.Bool(true),
                "voiceAiModel" to BackupValue.Str("model"),
                "reminderHour" to BackupValue.IntValue(20),
                "rating" to BackupValue.FloatValue(2.5f),
                "knownSet" to BackupValue.StrSet(setOf("a", "b")),
            ),
        )
    }

    @Test
    fun roundTripPreservesAllData() {
        val original = sampleData()
        val parsed = parseBackupData(original.toJsonString())

        assertTrue(parsed != null)
        parsed!!

        assertEquals(original.version, parsed.version)
        assertEquals(original.exportedAt, parsed.exportedAt)
        assertEquals(original.transactions, parsed.transactions)
        assertEquals(7, parsed.transactions.single().uid)
        assertEquals(original.budgetPeriods, parsed.budgetPeriods)
        assertEquals(3, parsed.budgetPeriods.single().id)
        assertEquals(original.archivedTransactions, parsed.archivedTransactions)
        assertEquals(9, parsed.archivedTransactions.single().uid)
        assertEquals(3, parsed.archivedTransactions.single().periodId)
        assertEquals(original.savedTags, parsed.savedTags)
        assertEquals(original.savedCategories, parsed.savedCategories)
        assertEquals(original.recurringTemplates, parsed.recurringTemplates)
        assertEquals(original.savingsGoals, parsed.savingsGoals)
        assertEquals(original.budgetPreferences, parsed.budgetPreferences)
        assertEquals(original.settingsPreferences, parsed.settingsPreferences)
    }

    @Test
    fun roundTripEmptyData() {
        val original = emptyData()
        val parsed = parseBackupData(original.toJsonString())

        assertTrue(parsed != null)
        parsed!!

        assertEquals(original.version, parsed.version)
        assertTrue(parsed.transactions.isEmpty())
        assertTrue(parsed.budgetPeriods.isEmpty())
        assertTrue(parsed.archivedTransactions.isEmpty())
        assertTrue(parsed.savedTags.isEmpty())
        assertTrue(parsed.savedCategories.isEmpty())
        assertTrue(parsed.recurringTemplates.isEmpty())
        assertTrue(parsed.savingsGoals.isEmpty())
        assertTrue(parsed.budgetPreferences.isEmpty())
        assertTrue(parsed.settingsPreferences.isEmpty())
    }

    @Test
    fun transactionWithNullCategoryRoundTrips() {
        val original = emptyData().copy(
            transactions = listOf(
                Transaction(TransactionType.SPENT, BigDecimal("10.00"), Date(0), "c", null)
                    .also { it.uid = 1 }
            )
        )
        val parsed = parseBackupData(original.toJsonString())

        assertTrue(parsed != null)
        parsed!!

        assertNull(parsed.transactions.single().category)
        assertEquals(1, parsed.transactions.single().uid)
    }

    @Test
    fun invalidJsonReturnsNull() {
        assertNull(parseBackupData("not json"))
        assertNull(parseBackupData(""))
    }

    @Test
    fun wrongAppTagReturnsNull() {
        val json = JSONObject()
            .put("app", "other")
            .put(BACKUP_VERSION_KEY, BACKUP_VERSION)
            .toString()
        assertNull(parseBackupData(json))
    }

    @Test
    fun unsupportedVersionReturnsNull() {
        val json = JSONObject()
            .put(BACKUP_APP_TAG_KEY, BACKUP_APP_TAG)
            .put(BACKUP_VERSION_KEY, BACKUP_VERSION + 1)
            .toString()
        assertNull(parseBackupData(json))
    }

    @Test
    fun missingSectionsDefaultToEmpty() {
        val json = JSONObject()
            .put(BACKUP_APP_TAG_KEY, BACKUP_APP_TAG)
            .put(BACKUP_VERSION_KEY, BACKUP_VERSION)
            .put("budgetPreferences", JSONObject())
            .toString()
        val parsed = parseBackupData(json)

        assertTrue(parsed != null)
        parsed!!

        assertTrue(parsed.transactions.isEmpty())
        assertTrue(parsed.budgetPeriods.isEmpty())
        assertTrue(parsed.archivedTransactions.isEmpty())
        assertTrue(parsed.savedTags.isEmpty())
        assertTrue(parsed.savedCategories.isEmpty())
        assertTrue(parsed.recurringTemplates.isEmpty())
        assertTrue(parsed.savingsGoals.isEmpty())
        assertTrue(parsed.budgetPreferences.isEmpty())
        assertTrue(parsed.settingsPreferences.isEmpty())
    }

    @Test
    fun backupValueAllTypesRoundTrip() {
        val values = listOf(
            BackupValue.Bool(true),
            BackupValue.IntValue(42),
            BackupValue.LongValue(9_000_000_000_000L),
            BackupValue.FloatValue(2.5f),
            BackupValue.Str("hello"),
            BackupValue.StrSet(setOf("a", "b")),
        )
        values.forEach { value ->
            assertEquals(value, BackupValue.fromJson(value.toJson()))
        }
        assertNull(BackupValue.fromJson(JSONObject()))
    }
}
