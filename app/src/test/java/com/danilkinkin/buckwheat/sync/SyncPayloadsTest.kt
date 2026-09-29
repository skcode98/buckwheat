package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPayloadsTest {

    private fun local(table: String, id: String, payload: String, deletedAt: Long? = null) =
        LocalRecord(
            table = table,
            id = id,
            updatedAt = 1_700_000_000_000L,
            version = 7,
            deletedAt = deletedAt,
            payload = payload,
            dirty = false,
            memberId = "member-1",
            familyId = "family-1",
            syncSeq = 42L,
        )

    private fun recordFor(
        table: String,
        id: String,
        payload: String,
        memberId: String? = null,
    ) = LocalRecord(
        table = table,
        id = id,
        updatedAt = 5L,
        version = 2,
        deletedAt = null,
        payload = payload,
        dirty = false,
        memberId = memberId,
        familyId = "family-1",
        syncSeq = 9L,
    )

    @Test
    fun thePayloadNeverCarriesTheIdOrAnySyncColumn() {
        val json = transaction.businessPayload()

        assertNull(json.opt("id"))
        listOf(
            "memberId", "familyId", "syncSeq", "updatedAt", "deletedAt", "version",
        ).forEach { assertNull(it, json.opt(it)) }
    }

    @Test
    fun aTransactionSurvivesARoundTrip() {
        val original = transaction.copy(memberId = "member-1", familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.TRANSACTIONS, original.id, original.businessPayload().toString(), "member-1")

        val decoded = JSONObject(record.payload).readTransaction(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun anArchivedTransactionSurvivesARoundTrip() {
        val original = archived.copy(memberId = "member-1", familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.ARCHIVED_TRANSACTIONS, original.id, original.businessPayload().toString(), "member-1")

        val decoded = JSONObject(record.payload).readArchivedTransaction(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun aBudgetPeriodSurvivesARoundTrip() {
        val original = period.copy(familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.BUDGET_PERIODS, original.id, original.businessPayload().toString())

        val decoded = JSONObject(record.payload).readBudgetPeriod(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun aSavedCategorySurvivesARoundTrip() {
        val original = category.copy(familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.SAVED_CATEGORIES, original.id, original.businessPayload().toString())

        val decoded = JSONObject(record.payload).readSavedCategory(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun aSavedTagSurvivesARoundTrip() {
        val original = tag.copy(familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.SAVED_TAGS, original.id, original.businessPayload().toString())

        val decoded = JSONObject(record.payload).readSavedTag(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun aRecurringTemplateSurvivesARoundTrip() {
        val original = recurring.copy(familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.RECURRING_TEMPLATES, original.id, original.businessPayload().toString())

        val decoded = JSONObject(record.payload).readRecurringTemplate(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun aSavingsGoalSurvivesARoundTrip() {
        val original = goal.copy(familyId = "family-1", syncSeq = 9L, updatedAt = 5L, version = 2)
        val record = recordFor(SyncTables.SAVINGS_GOALS, original.id, original.businessPayload().toString())

        val decoded = JSONObject(record.payload).readSavingsGoal(original.id).withSyncMeta(record)

        assertEquals(original, decoded)
    }

    @Test
    fun withSyncMetaCopiesTheMetadataOntoTheDecodedEntity() {
        val record = local(SyncTables.TRANSACTIONS, "t-1", transaction.businessPayload().toString())

        val decoded = JSONObject(record.payload).readTransaction(record.id).withSyncMeta(record)

        assertEquals("member-1", decoded.memberId)
        assertEquals("family-1", decoded.familyId)
        assertEquals(42L, decoded.syncSeq)
        assertEquals(1_700_000_000_000L, decoded.updatedAt)
        assertEquals(7, decoded.version)
        assertNull(decoded.deletedAt)
    }

    @Test
    fun withSyncMetaKeepsTheTombstoneTimestamp() {
        val record = local(SyncTables.SAVED_TAGS, "tag-1", "{}", deletedAt = 999L)

        val decoded = JSONObject(record.payload).readSavedTag(record.id).withSyncMeta(record)

        assertEquals(999L, decoded.deletedAt)
    }

    @Test
    fun anUnknownTransactionTypeFallsBackToSpent() {
        val json = transaction.businessPayload().put("type", "NOT_A_TYPE")

        assertEquals(TransactionType.SPENT, json.readTransaction("t-1").type)
    }

    @Test
    fun aNullCategoryStaysNull() {
        val json = transaction.copy(category = null).businessPayload()

        assertNull(json.readTransaction("t-1").category)
    }

    @Test
    fun anAbsentCategoryStaysNull() {
        val json = JSONObject().put("type", "SPENT").put("value", "1").put("spentAt", 5L)

        assertNull(json.readTransaction("t-1").category)
    }

    @Test
    fun anImportedPeriodKeepsItsFlag() {
        assertEquals(true, period.copy(isImported = true).businessPayload().readBudgetPeriod("p-1").isImported)
    }

    @Test
    fun aDisabledRecurringTemplateKeepsItsFlag() {
        assertFalse(recurring.copy(enabled = false).businessPayload().readRecurringTemplate("r-1").enabled)
    }

    @Test
    fun aBigDecimalKeepsItsScaleThroughThePayload() {
        val json = transaction.copy(value = BigDecimal("12.50")).businessPayload()

        assertEquals(0, json.readTransaction("t-1").value.compareTo(BigDecimal("12.50")))
    }

    @Test
    fun anUnenrolledEntityReportsNoFamily() {
        assertNull(category.businessPayload().readSavedCategory("c-1").familyId)
    }

    private val transaction = Transaction(
        id = "t-1",
        type = TransactionType.SPENT,
        value = BigDecimal("10.25"),
        date = Date(1_700_000_000_000L),
        comment = "coffee",
        category = "food",
    )

    private val archived = ArchivedTransaction(
        id = "a-1",
        type = TransactionType.INCOME,
        value = BigDecimal("500.00"),
        date = Date(1_700_000_000_000L),
        comment = "salary",
        category = null,
        periodId = "p-1",
    )

    private val period = BudgetPeriod(
        id = "p-1",
        budget = BigDecimal("1000.00"),
        startDate = Date(1_699_000_000_000L),
        finishDate = Date(1_702_000_000_000L),
        actualFinishDate = null,
        currencyCode = "USD",
        totalSpent = BigDecimal("250.75"),
        isImported = false,
    )

    private val category = SavedCategory(id = "c-1", name = "Food", emoji = "🍔")

    private val tag = SavedTag(id = "tag-1", name = "work")

    private val recurring = RecurringTemplate(
        id = "r-1",
        amount = BigDecimal("30.00"),
        comment = "rent",
        dayOfMonth = 1,
        enabled = true,
    )

    private val goal = SavingsGoal(
        id = "g-1",
        name = "Laptop",
        targetAmount = BigDecimal("2000.00"),
        currentAmount = BigDecimal("150.00"),
        deadline = Date(1_800_000_000_000L),
        createdAt = Date(1_700_000_000_000L),
        completed = false,
    )
}
