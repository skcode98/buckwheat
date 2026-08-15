package com.danilkinkin.buckwheat.history

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import com.danilkinkin.buckwheat.data.entities.Transaction
import java.math.BigDecimal
import java.time.LocalDate

// One rendered list entry: a full day card with its transactions and the day total.
// The key is the day's date so editing a transaction updates the card in place while a
// day that appears or disappears animates in/out as a whole.
data class RowEntity(
    val key: String,
    var contentHash: String? = null,
    val day: LocalDate,
    val transactions: List<Transaction>,
    val firstTransactionIndex: Int = 0,
    var dayTotal: BigDecimal? = null,
)

@Suppress("UpdateTransitionLabel", "TransitionPropertiesLabel")
@SuppressLint("ComposableNaming", "UnusedTransitionTargetStateParameter")
/**
 * @param state Use [updateAnimatedItemsState].
 */
inline fun LazyListScope.animatedItemsIndexed(
    state: List<AnimatedItem<RowEntity>>,
    enterTransition: EnterTransition = expandVertically(),
    exitTransition: ExitTransition = shrinkVertically(),
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: RowEntity) -> Unit
) {
    items(
        state.size,
        { keyIndex: Int -> state[keyIndex].id }
    ) { index ->

        val item = state[index]

        key(item.id) {
            AnimatedVisibility(
                visibleState = item.visibility,
                enter = enterTransition,
                exit = exitTransition
            ) {
                itemContent(index, item.item)
            }
        }
    }
}

@Composable
fun updateAnimatedItemsState(
    newList: List<RowEntity>
): State<List<AnimatedItem<RowEntity>>> {

    val state = remember { mutableStateOf(emptyList<AnimatedItem<RowEntity>>()) }
    val firstInject = remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        state.value = emptyList()
        onDispose {
        }
    }

    LaunchedEffect(newList) {
        if (state.value == newList) {
            return@LaunchedEffect
        }
        val oldList = state.value.toList()

        val oldKeyToIndex = HashMap<String, Int>()
        oldList.forEachIndexed { index, item -> oldKeyToIndex[item.item.key] = index }

        // Build the composite directly by key instead of consuming DiffUtil's event stream.
        // DiffUtil reports insert positions in the coordinate space of the previous composite,
        // which can still contain rows animating out (a keystroke cancels the previous exit
        // animation). Those lingering rows inflate the positions beyond newList.size and made
        // the old dispatch read newList[position + i] out of bounds (IndexOutOfBoundsException
        // when searching or editing a record's date).
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
                    visibility = MutableTransitionState(firstInject.value),
                    row
                )
                animated.visibility.targetState = true
                compositeList.add(animated)
                oldIndexOfComposite.add(-1)
            }
        }

        // Rows that disappeared keep their spot as long as the exit animation runs.
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

        if (state.value != compositeList) {
            state.value = compositeList
        }
        firstInject.value = false
        val initialAnimation = androidx.compose.animation.core.Animatable(1.0f)
        initialAnimation.animateTo(0f)
        state.value = state.value.filter { it.visibility.targetState }
    }

    return state
}

private var animatedItemIdCounter = 0L

data class AnimatedItem<T>(
    val visibility: MutableTransitionState<Boolean>,
    var item: T,
    val id: Long = animatedItemIdCounter++,
) {

    override fun hashCode(): Int {
        return item?.hashCode() ?: 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnimatedItem<*>

        return item == other.item
    }
}
