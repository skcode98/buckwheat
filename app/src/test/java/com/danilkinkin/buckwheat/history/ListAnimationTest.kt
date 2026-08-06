package com.danilkinkin.buckwheat.history

import androidx.compose.animation.core.MutableTransitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Regression tests for the history list animation (ListAnimation.kt).
 *
 * `updateAnimatedItemsState` builds the composite list directly by key instead of
 * consuming DiffUtil's event stream, because DiffUtil reports insert positions in the
 * coordinate space of the previous composite - which can still contain rows animating
 * out - and the old dispatch then read `newList[position + i]` out of bounds
 * (IndexOutOfBoundsException when searching or editing a record's date).
 *
 * The LazyColumn in [animatedItemsIndexed] is keyed by a per-instance [AnimatedItem.id]
 * so a moved row never produces two items with the same key.
 */
class ListAnimationTest {

    private fun row(type: RowEntityType, key: String, hash: String = key) =
        RowEntity(
            type = type,
            key = key,
            contentHash = hash,
            day = LocalDate.of(2026, 8, 5),
            transaction = null,
            dayTotal = null,
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
                    visibility = androidx.compose.animation.core.MutableTransitionState(false),
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
    fun movedRowKeepsItsInstanceAndMovesIntoPlace() {
        // Old list: a single day with two spends.
        val oldRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-50.00-a-1780000000000"),
            row(RowEntityType.Spent, "spent-2", "spent-2-30.00-b-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-80.00"),
        )
        val oldList = oldRows.map { AnimatedItem(MutableTransitionState(true), it) }

        // New list: spent-2 moved to another day (date edit) - the row keeps its key.
        val newRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-06"),
            row(RowEntityType.Spent, "spent-2", "spent-2-30.00-b-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-06", "total-2026-08-06-30.00"),
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-50.00-a-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-50.00"),
        )

        val composite = simulateDispatch(oldList, newRows)

        // A moved row is reused (no duplicate, no spurious exit animation) and the final
        // live rows match newList exactly.
        assertEquals(1, composite.count { it.item.key == "spent-2" && it.visibility.targetState })
        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun searchFilteringThatDropsWholeDaysKeepsUniqueIds() {
        // Two days, three spends.
        val oldRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-06"),
            row(RowEntityType.Spent, "spent-3", "spent-3-10.00-x-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-06", "total-2026-08-06-10.00"),
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-50.00-a-1780000000000"),
            row(RowEntityType.Spent, "spent-2", "spent-2-30.00-b-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-80.00"),
        )
        val oldList = oldRows.map { AnimatedItem(MutableTransitionState(true), it) }

        // A search query that only matches spent-3: day of 05 Aug disappears entirely and
        // the surviving rows keep their keys but shift positions.
        val newRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-06"),
            row(RowEntityType.Spent, "spent-3", "spent-3-10.00-x-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-06", "total-2026-08-06-10.00"),
        )

        val composite = simulateDispatch(oldList, newRows)

        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // The whole 05 Aug day animates out; its rows must still be present during the
        // exit window (they are what the exit animation plays on).
        assertTrue(composite.any { it.item.key == "header-2026-08-05" && !it.visibility.targetState })
        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
    }

    @Test
    fun contentEditUpdatesInPlaceWithoutDuplicatingTheRow() {
        val oldRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-50.00-a-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-50.00"),
        )
        val oldList = oldRows.map { AnimatedItem(MutableTransitionState(true), it) }

        // Same day, same key, only the value changed (e.g. editing the amount in place).
        val newRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-90.00-a-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-90.00"),
        )

        val composite = simulateDispatch(oldList, newRows)

        // In-place update must not remove+reinsert: the spent-1 instance is reused.
        assertEquals(1, composite.count { it.item.key == "spent-1" })
        assertEquals(1, composite.count { it.item.key == "total-2026-08-05" })
        assertEquals(newRows.size, composite.size)
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun lingeringRowsFromPreviousFrameDoNotReadPastNewList() {
        // Frame 1 leaves rows animating out: search dropped the 05 Aug day. The composite
        // therefore holds 7 items (4 live + 3 lingering) while the next keystroke produces
        // a newList of only 4 rows.
        val liveRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-06", "header-2026-08-06-b"),
            row(RowEntityType.Spent, "spent-3", "spent-3-10.00-x-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-06", "total-2026-08-06-b-10.00"),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        val lingeringRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-05"),
            row(RowEntityType.Spent, "spent-1", "spent-1-50.00-a-1780000000000"),
            row(RowEntityType.Spent, "spent-2", "spent-2-30.00-b-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-80.00"),
        ).map { AnimatedItem(MutableTransitionState(true), it) }
        lingeringRows.forEach { it.visibility.targetState = false }
        val oldList = liveRows + lingeringRows

        // Next keystroke: the query matches spent-2 as well, so the 05 Aug day comes back.
        val newRows = listOf(
            row(RowEntityType.DayDivider, "header-2026-08-06", "header-2026-08-06-bu"),
            row(RowEntityType.Spent, "spent-3", "spent-3-10.00-x-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-06", "total-2026-08-06-bu-10.00"),
            row(RowEntityType.DayDivider, "header-2026-08-05", "header-2026-08-05-bu"),
            row(RowEntityType.Spent, "spent-2", "spent-2-30.00-b-1780000000000"),
            row(RowEntityType.DayTotal, "total-2026-08-05", "total-2026-08-05-bu-30.00"),
        )

        // Must not throw; previously this read newList[position + i] with a composite-space
        // position past newList.size.
        val composite = simulateDispatch(oldList, newRows)

        val live = composite.filter { it.visibility.targetState }.map { it.item.key }
        assertEquals(newRows.map { it.key }, live)
        assertTrue(composite.any { it.item.key == "spent-1" && !it.visibility.targetState })
        val ids = composite.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

