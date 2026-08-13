package com.danilkinkin.buckwheat.data.categories

import java.math.BigDecimal
import java.math.RoundingMode

// One-tap split of the current budget across all categories, driven by each category's
// "requirement" = its typical monthly spend (averaged over the given periods). Used by the
// common Auto-assign budget button in the category caps sheet; the user can then reassign
// any cap. Pure so it is trivially unit-testable.

private fun CategoryKey.categoryName(): String = when (this) {
    is CategoryKey.BuiltIn -> category.name
    is CategoryKey.Custom -> name
}

// Average per-period spend for each category across the supplied periods. A category absent
// from a period counts as zero there; periods with no spend at all are excluded from the
// average so a quiet month never dilutes the requirement. Deterministic (sorted by name) and
// drops categories whose average is non-positive.
fun averageCategorySpend(
    periods: List<List<Pair<CategoryKey, BigDecimal>>>,
): Map<String, BigDecimal> {
    val nonEmpty = periods.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return emptyMap()

    val totals = linkedMapOf<String, BigDecimal>()
    nonEmpty.forEach { periodTotals ->
        periodTotals.forEach { (key, value) ->
            val name = key.categoryName()
            totals[name] = (totals[name] ?: BigDecimal.ZERO) + value
        }
    }
    val periodCount = nonEmpty.size.toBigDecimal()
    return totals
        .mapValues { (_, total) -> total.divide(periodCount, 2, RoundingMode.HALF_UP) }
        .filterValues { it > BigDecimal.ZERO }
        .toSortedMap()
}

// Splits `budget` equally among `categories` (sorted by name). Amounts are floored to two
// decimals and the last category absorbs the remainder so the caps sum exactly to the budget.
fun evenlySplitBudget(
    budget: BigDecimal,
    categories: List<String>,
): Map<String, BigDecimal> {
    if (budget <= BigDecimal.ZERO || categories.isEmpty()) return emptyMap()
    val sorted = categories.sorted()
    val share = budget
        .divide(sorted.size.toBigDecimal(), 2, RoundingMode.FLOOR)
    var allocated = BigDecimal.ZERO
    return linkedMapOf<String, BigDecimal>().apply {
        sorted.forEachIndexed { index, name ->
            val amount = if (index == sorted.lastIndex) budget - allocated else share
            put(name, amount)
            allocated += amount
        }
    }
}

// Splits `budget` among categories in proportion to their average per-period spend. Shares are
// floored to two decimals and the last category (sorted order) absorbs the remainder so the
// caps sum exactly to the budget.
fun allocateBudgetByRequirement(
    budget: BigDecimal,
    categoryAverages: Map<String, BigDecimal>,
): Map<String, BigDecimal> {
    if (budget <= BigDecimal.ZERO || categoryAverages.isEmpty()) return emptyMap()
    val positive = categoryAverages
        .filterValues { it > BigDecimal.ZERO }
        .toSortedMap()
    if (positive.isEmpty()) return emptyMap()
    val totalAverage = positive.values.fold(BigDecimal.ZERO) { acc, value -> acc + value }
    if (totalAverage <= BigDecimal.ZERO) return emptyMap()

    var allocated = BigDecimal.ZERO
    val entries = positive.entries.toList()
    return linkedMapOf<String, BigDecimal>().apply {
        entries.forEachIndexed { index, (name, average) ->
            val amount = if (index == entries.lastIndex) {
                budget - allocated
            } else {
                average
                    .divide(totalAverage, 8, RoundingMode.HALF_UP)
                    .multiply(budget)
                    .setScale(2, RoundingMode.FLOOR)
            }
            put(name, amount)
            allocated += amount
        }
    }
}

// The full new caps map for the Auto-assign button. Each category gets its share of `budget`
// proportional to its typical monthly spend; a category with no spend history is treated as
// having the mean requirement so every category still receives a cap (nothing is left empty),
// and with no spend history at all the budget is split evenly. The result replaces the
// existing caps so the user starts from the allocation and can reassign per category.
fun autoAssignCategoryCaps(
    budget: BigDecimal,
    categories: List<String>,
    periods: List<List<Pair<CategoryKey, BigDecimal>>>,
): Map<String, BigDecimal> {
    if (budget <= BigDecimal.ZERO || categories.isEmpty()) return emptyMap()
    val averages = averageCategorySpend(periods)
    if (averages.isEmpty()) return evenlySplitBudget(budget, categories)

    val fallbackAverage = averages.values
        .fold(BigDecimal.ZERO) { acc, value -> acc + value }
        .divide(averages.size.toBigDecimal(), 4, RoundingMode.HALF_UP)
    val effective = categories.distinct().sorted().associateWith { name ->
        averages[name] ?: fallbackAverage
    }
    return allocateBudgetByRequirement(budget, effective)
}

// The requirement basis for the Auto-assign button: completed (archived) periods only when any
// exist, so a half-finished current month never dilutes the monthly requirement; the current
// (possibly partial) period is only used as the basis when there is no completed history yet.
// Pure so it is trivially unit-testable.
fun requirementPeriods(
    currentPeriod: List<Pair<CategoryKey, BigDecimal>>,
    archivedPeriods: List<List<Pair<CategoryKey, BigDecimal>>>,
): List<List<Pair<CategoryKey, BigDecimal>>> =
    if (archivedPeriods.isNotEmpty()) {
        archivedPeriods
    } else {
        listOf(currentPeriod).filter { it.isNotEmpty() }
    }
