package com.danilkinkin.buckwheat.history

import com.danilkinkin.buckwheat.data.categories.CategoryKey
import com.danilkinkin.buckwheat.data.categories.SpendCategory
import com.danilkinkin.buckwheat.data.entities.ArchivedTransaction
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.toDate
import com.danilkinkin.buckwheat.util.toLocalDate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

class HistoryRowsTest {

    private companion object {
        const val HISTORY_LOADER_THREAD = "history-loader"
    }

    private fun spent(
        uid: Int,
        value: String,
        comment: String = "",
        date: LocalDateTime = LocalDateTime.of(2026, 8, 5, 12, 0),
        category: String? = null,
    ): Transaction = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = date.toDate(),
        comment = comment,
        category = category,
    ).also { it.uid = uid }

    private fun archived(
        uid: Int,
        value: String,
        comment: String,
        date: LocalDateTime,
    ): ArchivedTransaction = ArchivedTransaction(
        periodId = 1,
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = date.toDate(),
        comment = comment,
    ).also { it.uid = uid }

    @Test
    fun groupsTransactionsIntoDayCardsNewestFirst() {
        val rows = composeHistoryRows(
            periodSpends = listOf(
                spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0)),
                spent(2, "50", "coffee", LocalDateTime.of(2026, 8, 5, 8, 0)),
                spent(3, "200", "dinner", LocalDateTime.of(2026, 8, 6, 20, 0)),
            ),
            archivedTransactions = emptyList(),
            searchQuery = "",
        )

        assertEquals(listOf("day-2026-08-06", "day-2026-08-05"), rows.map { it.key })
        assertEquals(LocalDate.of(2026, 8, 6), rows[0].day)
        assertEquals(listOf(3), rows[0].transactions.map { it.uid })
        assertEquals(BigDecimal("200"), rows[0].dayTotal)
        assertEquals(2, rows[0].firstTransactionIndex)

        assertEquals(listOf(2, 1), rows[1].transactions.map { it.uid })
        assertEquals(BigDecimal("150"), rows[1].dayTotal)
        assertEquals(0, rows[1].firstTransactionIndex)
    }

    @Test
    fun emptyPeriodProducesNoRows() {
        assertEquals(
            emptyList<RowEntity>(),
            composeHistoryRows(emptyList(), emptyList(), searchQuery = ""),
        )
    }

    @Test
    fun archivedTransactionsIncludedOnlyWhileSearching() {
        val period = listOf(spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0)))
        val archivedTx = archived(9, "40", "ancient lunch", LocalDateTime.of(2025, 1, 2, 9, 0))

        assertEquals(listOf("day-2026-08-05"), composeHistoryRows(period, listOf(archivedTx), "").map { it.key })

        val searching = composeHistoryRows(period, listOf(archivedTx), "lunch")
        assertEquals(listOf("day-2026-08-05", "day-2025-01-02"), searching.map { it.key })
        assertEquals(listOf(9), searching.last().transactions.map { it.uid })
    }

    @Test
    fun filterByDayAndCategoryDropsOtherTransactions() {
        val spends = listOf(
            spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0)),
            spent(2, "50", "bus", LocalDateTime.of(2026, 8, 6, 9, 0)),
        )

        val onlyDay = composeHistoryRows(spends, emptyList(), "", onlyDay = LocalDate.of(2026, 8, 6))
        assertEquals(listOf("day-2026-08-06"), onlyDay.map { it.key })

        val onlyCategory = composeHistoryRows(
            spends,
            emptyList(),
            "",
            onlyCategoryKey = CategoryKey.BuiltIn(SpendCategory.FOOD),
        )
        assertEquals(listOf("day-2026-08-05"), onlyCategory.map { it.key })
    }

    @Test
    fun dayCardContentChangesWhenATransactionValueChanges() {
        val before = composeHistoryRows(
            listOf(spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0))),
            emptyList(),
            "",
        )
        val after = composeHistoryRows(
            listOf(spent(1, "90", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0))),
            emptyList(),
            "",
        )

        assertEquals("day-2026-08-05", before.first().key)
        assertEquals(before.first().key, after.first().key)
        assertTrue(before.first().transactions != after.first().transactions)
        assertEquals(BigDecimal("90"), after.first().dayTotal)
    }

    @Test
    fun resolvesEachTransactionDayExactlyOnce() {
        var calls = 0

        composeHistoryRows(
            periodSpends = listOf(
                spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0)),
                spent(2, "50", "coffee", LocalDateTime.of(2026, 8, 5, 8, 0)),
                spent(3, "25", "tea", LocalDateTime.of(2026, 8, 5, 9, 0)),
            ),
            archivedTransactions = emptyList(),
            searchQuery = "",
            onlyDay = LocalDate.of(2026, 8, 5),
            toDay = { date ->
                calls++
                date.toLocalDate()
            },
        )

        assertEquals(3, calls)
    }

    @Test
    fun buildsHistoryRowsOnTheGivenDispatcher() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, HISTORY_LOADER_THREAD)
        }.asCoroutineDispatcher()

        try {
            var threadName: String? = null
            val rows = loadHistoryRows(
                dispatcher = dispatcher,
                periodSpends = listOf(
                    spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0)),
                    spent(2, "50", "coffee", LocalDateTime.of(2026, 8, 5, 8, 0)),
                ),
                archivedTransactions = emptyList(),
                searchQuery = "",
                toDay = { date ->
                    threadName = Thread.currentThread().name
                    date.toLocalDate()
                },
            )

            assertEquals(1, rows.size)
            assertEquals(HISTORY_LOADER_THREAD, threadName)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun persistedCategoryIsNotFilteredOutByKeywordFallback() {
        val tx = spent(1, "100", "lunch", LocalDateTime.of(2026, 8, 5, 12, 0), category = "HEALTH")
        val rows = composeHistoryRows(
            listOf(tx),
            emptyList(),
            "",
            onlyCategoryKey = CategoryKey.BuiltIn(SpendCategory.HEALTH),
        )
        assertEquals(1, rows.size)
        assertEquals(listOf(1), rows.single().transactions.map { it.uid })
    }
}
