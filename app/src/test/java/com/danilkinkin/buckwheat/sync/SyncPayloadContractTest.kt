package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.BudgetPeriod
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.SavedCategory
import com.danilkinkin.buckwheat.data.entities.SavedTag
import com.danilkinkin.buckwheat.data.entities.SavingsGoal
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.io.File
import java.math.BigDecimal
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPayloadContractTest {

    @Test
    fun everyTableTheServerKnowsHasAPayloadWithTheSameKeys() {
        val spec = serverSpec()

        assertEquals(SyncTables.ALL.size, spec.size)

        spec.forEach { (table, serverKeys) ->
            assertEquals(
                "payload keys for $table do not match the server column spec",
                serverKeys,
                androidKeys(table),
            )
        }
    }

    /**
     * Compared against [SyncTables.ALL] rather than a literal set.
     *
     * A hardcoded set is what made this test useless for catching a *new* table: the app added one, the
     * server did not, and the test still passed because it was only checking a list both sides happened
     * to have written down independently. Deriving the expectation from the single source of truth
     * means a table the server does not know about is now a failure instead of a silent drop: the
     * client would push it, the server would reject the whole sync, and every member's local changes
     * would stop uploading.
     */
@Test
    fun theServerKnowsEveryTableTheAppCanPush() {
        assertEquals(
            SyncTables.ALL.toSet(),
            serverSpec().keys,
        )
    }

    private fun androidKeys(table: String): Set<String> {
        val payload = when (table) {
            SyncTables.TRANSACTIONS -> transaction.businessPayload()
            SyncTables.ARCHIVED_TRANSACTIONS -> archived.businessPayload()
            SyncTables.BUDGET_PERIODS -> period.businessPayload()
            SyncTables.SAVED_CATEGORIES -> category.businessPayload()
            SyncTables.SAVED_TAGS -> tag.businessPayload()
            SyncTables.RECURRING_TEMPLATES -> recurring.businessPayload()
            SyncTables.SAVINGS_GOALS -> goal.businessPayload()

            else -> throw AssertionError("no payload producer is registered for $table")
        }
        return payload.keys().asSequence().toSet()
    }

    private fun serverSpec(): Map<String, Set<String>> {
        val file = serverSpecFile()
        val columns = linkedMapOf<String, MutableSet<String>>()
        var insideAll = false
        var awaitingTableName = false
        file.readLines().forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith(SPEC_START) -> insideAll = true
                !insideAll -> Unit
                trimmed.startsWith(TABLE_SPEC) -> awaitingTableName = true
                awaitingTableName -> {
                    val name = tableName.find(line)
                        ?: throw AssertionError("TableSpec without a name in ${file.name}: $line")
                    columns.getOrPut(name.groupValues[1]) { linkedSetOf() }
                    awaitingTableName = false
                }
                trimmed.startsWith(PAYLOAD_COLUMN) -> {
                    val key = columnKey.find(line)
                        ?: throw AssertionError("PayloadColumn without a key in ${file.name}: $line")
                    val current = columns.values.lastOrNull()
                        ?: throw AssertionError("PayloadColumn outside of a TableSpec in ${file.name}: $line")
                    current.add(key.groupValues[1])
                }
            }
        }
        return columns
    }

    private fun serverSpecFile(): File {
        val start = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        return generateSequence(File(start)) { it.parentFile }
            .map { File(it, SERVER_SPEC_PATH) }
            .firstOrNull { it.isFile }
            ?: throw AssertionError("$SERVER_SPEC_PATH not found in any parent of $start")
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

    private companion object {
        const val SERVER_SPEC_PATH = "server/src/main/kotlin/family/sync/sync/SyncStore.kt"
        const val SPEC_START = "val ALL: List<TableSpec>"
        const val TABLE_SPEC = "TableSpec("
        const val PAYLOAD_COLUMN = "PayloadColumn("
        val tableName = Regex("""name = "([^"]+)"""")
        val columnKey = Regex("""PayloadColumn\("([^"]+)"""")
    }
}
