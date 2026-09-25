package com.danilkinkin.buckwheat.history

import androidx.compose.animation.core.MutableTransitionState
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Date

class ListAnimationTest {

    private var nextUid = 1

    private fun tx(comment: String): Transaction =
        Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(10),
            date = Date(1780000000000 + nextUid * 1000L),
            comment = comment,
        ).also { it.uid = nextUid++ }

    private fun row(
        key: String,
        vararg transactions: Transaction,
    ): RowEntity = RowEntity(
        key = key,
        day = LocalDate.of(2026, 8, 5),
        transactions = transactions.toList(),
        firstTransactionIndex = 0,
        dayTotal = transactions.sumOf { it.value },
    )

    private fun simulateDispatch(
        oldList: List<AnimatedItem<RowEntity>>,
        newList: List<RowEntity>,
    ): List<AnimatedItem<RowEntity>> =
        dispatchAnimatedItems(oldList, newList, firstInject = false).items

    @Test
    fun transactionMovedToAnotherDayUpdatesBothDayCardsInPlace() {
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val oldList = listOf(
            row("day-2026-08-05", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val newRows = listOf(
            row("day-2026-08-06", tx2),
            row("day-2026-08-05", tx1),
        )

        val composite = simulateDispatch(oldList, newRows)

        assertEquals(1, composite.count { it.item.key == "day-2026-08-05" && it.visibility.targetState })
        assertEquals(1, composite.count { it.item.key == "day-2026-08-06" && it.visibility.targetState })
        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun searchFilteringThatDropsWholeDaysKeepsUniqueIds() {
        val tx3 = tx("bus")
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val oldList = listOf(
            row("day-2026-08-06", tx3),
            row("day-2026-08-05", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val newRows = listOf(
            row("day-2026-08-06", tx3),
        )

        val composite = simulateDispatch(oldList, newRows)

        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        assertTrue(composite.any { it.item.key == "day-2026-08-05" && !it.visibility.targetState })
        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
    }

    @Test
    fun contentEditUpdatesInPlaceWithoutDuplicatingTheDay() {
        val tx1 = tx("coffee")
        val oldList = listOf(
            row("day-2026-08-05", tx1),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val edited = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(90),
            date = tx1.date,
            comment = tx1.comment,
        ).also { it.uid = tx1.uid }
        val newRows = listOf(
            row("day-2026-08-05", edited),
        )

        val composite = simulateDispatch(oldList, newRows)

        assertEquals(1, composite.count { it.item.key == "day-2026-08-05" })
        assertEquals(newRows.size, composite.size)
        assertEquals(BigDecimal(90), composite.first { it.item.key == "day-2026-08-05" }.item.dayTotal)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun onlyTheFirstDispatchConsumesFirstInject() {
        val rows = listOf(row("day-2026-08-05", tx("coffee")))
        var firstInject = true

        val first = dispatchAnimatedItems(emptyList(), rows, firstInject)
        assertTrue(first.consumedFirstInject)
        firstInject = false

        val second = dispatchAnimatedItems(first.items, rows, firstInject)
        val third = dispatchAnimatedItems(second.items, rows, firstInject)

        assertTrue(!second.consumedFirstInject)
        assertTrue(!third.consumedFirstInject)
        assertEquals(1, third.items.size)
        assertTrue(third.items.all { it.visibility.targetState })
    }

    @Test
    fun lingeringRowsFromPreviousFrameDoNotReadPastNewList() {
        val tx3 = tx("bus")
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val liveRows = listOf(
            row("day-2026-08-06", tx3),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        val lingeringRows = listOf(
            row("day-2026-08-05", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        lingeringRows.forEach { it.visibility.targetState = false }
        val oldList = liveRows + lingeringRows

        val newRows = listOf(
            row("day-2026-08-06", tx3),
        )

        val composite = simulateDispatch(oldList, newRows)

        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        assertTrue(composite.any { it.item.key == "day-2026-08-05" && !it.visibility.targetState })
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
