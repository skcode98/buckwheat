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

/**
 * Regression tests for the history list animation (ListAnimation.kt).
 *
 * `updateAnimatedItemsState` builds the composite list directly by key instead of
 * consuming DiffUtil's event stream, because DiffUtil reports insert positions in the
 * coordinate space of the previous composite - which can still contain rows animating
 * out - and the old dispatch then read `newList[position + i]` out of bounds
 * (IndexOutOfBoundsException when searching or editing a record's date).
 *
 * With the day-card model each `RowEntity` is one full day, so a single in-place edit
 * updates the whole card without an animation while whole days animate in/out by key.
 * The LazyColumn in [animatedItemsIndexed] is keyed by a per-instance [AnimatedItem.id]
 * so a moved card never produces two items with the same key.
 */
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

    /**
     * Simulates the composite construction performed by `updateAnimatedItemsState` and
     * returns the composite list exactly as it would be assigned to `state.value`,
     * including the animating-out (lingering) rows that are still present during the
     * exit-animation window.
     */
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
        // Old list: one day with two spends.
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val oldList = listOf(
            row("day-2026-08-05", "day-2026-08-05-a-b", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        // New list: tx2 was moved to the next day (date edit) - both day cards keep their keys.
        val newRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-b", tx2),
            row("day-2026-08-05", "day-2026-08-05-a", tx1),
        )

        val composite = simulateDispatch(oldList, newRows)

        // The day cards are reused (no duplicate, no spurious exit animation) and the final
        // live rows match newList exactly.
        assertEquals(1, composite.count { it.item.key == "day-2026-08-05" && it.visibility.targetState })
        assertEquals(1, composite.count { it.item.key == "day-2026-08-06" && it.visibility.targetState })
        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun searchFilteringThatDropsWholeDaysKeepsUniqueIds() {
        // Two days, three spends.
        val tx3 = tx("bus")
        val tx1 = tx("coffee")
        val tx2 = tx("lunch")
        val oldList = listOf(
            row("day-2026-08-06", "day-2026-08-06-x", tx3),
            row("day-2026-08-05", "day-2026-08-05-a-b", tx1, tx2),
        ).map { AnimatedItem(MutableTransitionState(true), it) }

        // A search query that only matches tx3: the 05 Aug day disappears entirely and the
        // surviving card keeps its key.
        val newRows = listOf(
            row("day-2026-08-06", "day-2026-08-06-x", tx3),
        )

        val composite = simulateDispatch(oldList, newRows)

        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // The whole 05 Aug card animates out; it must still be present during the exit window.
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

        // Same day, same key, only the value changed (e.g. editing the amount in place).
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

        // In-place update must not remove+reinsert: the day-card instance is reused.
        assertEquals(1, composite.count { it.item.key == "day-2026-08-05" })
        assertEquals(newRows.size, composite.size)
        assertEquals(BigDecimal(90), composite.first { it.item.key == "day-2026-08-05" }.item.dayTotal)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun lingeringRowsFromPreviousFrameDoNotReadPastNewList() {
        // Frame 1 leaves a day animating out: search dropped the 05 Aug day. The composite
        // therefore holds 2 items (1 live + 1 lingering) while the next keystroke produces
        // a newList of only 1 row.
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

        // Next keystroke: the query narrows further, so the 05 Aug day stays dropped and the
        // surviving card's hash changes. Must not throw; previously this read
        // newList[position + i] with a composite-space position past newList.size.
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
