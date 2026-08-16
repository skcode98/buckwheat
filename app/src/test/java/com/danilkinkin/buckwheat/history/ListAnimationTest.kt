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
        hash: String = key,
        vararg transactions: Transaction,
    ): RowEntity = RowEntity(
        key = key,
        contentHash = hash,
        day = LocalDate.of(2026, 8, 5),
        transactions = transactions.toList(),
        firstTransactionIndex = 0,
        dayTotal = transactions.sumOf { it.value },
    )

    private fun simulateDispatch(
        oldList: List<AnimatedItem<RowEntity>>,
        newList: List<RowEntity>,
    ): List<AnimatedItem<RowEntity>> {
        val oldKeyToIndex = HashMap<String, Int>()
        oldList.forEachIndexed { index, item -> oldKeyToIndex[item.item.key] = index }

        val consumedOld = BooleanArray(oldList.size)
        val compositeList = ArrayList<AnimatedItem<RowEntity>>(newList.size)
        val oldIndexOfComposite = ArrayList<Int>(newList.size)

        newList.forEach { row ->
            val oldIndex = oldKeyToIndex[row.key]
            if (oldIndex != null && !consumedOld[oldIndex]) {
                consumedOld[oldIndex] = true
                val animated = oldList[oldIndex]
                if (animated.item.contentHash != row.contentHash) {
                    animated.item = row
                }
                animated.visibility.targetState = true
                compositeList.add(animated)
                oldIndexOfComposite.add(oldIndex)
            } else {
                val animated = AnimatedItem(
                    visibility = MutableTransitionState(false),
                    row,
                )
                animated.visibility.targetState = true
                compositeList.add(animated)
                oldIndexOfComposite.add(-1)
            }
        }

        for (oldIndex in oldList.indices) {
            if (!consumedOld[oldIndex]) {
                val animated = oldList[oldIndex]
                animated.visibility.targetState = false
                val nextKept = oldIndexOfComposite.indexOfFirst { it > oldIndex }
                val insertAt = if (nextKept < 0) compositeList.size else nextKept
                compositeList.add(insertAt, animated)
                oldIndexOfComposite.add(insertAt, -1)
            }
        }

        return compositeList
    }

    @Test
    fun transactionMovedToAnotherDayUpdatesBothDayCardsInPlace() {
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val oldList = listOf(
            row("day-2026-08-05", "day-2026-08-05-a-b", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val newRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-b", tx2),
            row("day-2026-08-05", "day-2026-08-05-a", tx1),
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
            row("day-2026-08-06", "day-2026-08-06-x", tx3),
            row("day-2026-08-05", "day-2026-08-05-a-b", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val newRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-x", tx3),
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
            row("day-2026-08-05", "day-2026-08-05-50.00", tx1),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        val edited = Transaction(
            type = TransactionType.SPENT,
            value = BigDecimal(90),
            date = tx1.date,
            comment = tx1.comment,
        ).also { it.uid = tx1.uid }
        val newRows = listOf(
            row("day-2026-08-05", "day-2026-08-05-90.00", edited),
        )

        val composite = simulateDispatch(oldList, newRows)

        assertEquals(1, composite.count { it.item.key == "day-2026-08-05" })
        assertEquals(newRows.size, composite.size)
        assertEquals(BigDecimal(90), composite.first { it.item.key == "day-2026-08-05" }.item.dayTotal)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun lingeringRowsFromPreviousFrameDoNotReadPastNewList() {
        val tx3 = tx("bus")
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val liveRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-b", tx3),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        val lingeringRows = listOf(
            row("day-2026-08-05", "day-2026-08-05-a-b", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        lingeringRows.forEach { it.visibility.targetState = false }
        val oldList = liveRows + lingeringRows

        val newRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-bu", tx3),
        )

        val composite = simulateDispatch(oldList, newRows)

        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        assertTrue(composite.any { it.item.key == "day-2026-08-05" && !it.visibility.targetState })
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
