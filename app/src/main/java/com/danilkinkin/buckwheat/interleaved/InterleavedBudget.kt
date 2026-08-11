package com.danilkinkin.buckwheat.interleaved

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

// Budget frequencies for interleaved category budgets. A category with a schedule entry
// rolls over on its own window (independent of the main budget period); DAILY keeps the
// plain-cap semantics (no window, no rollover).
enum class CategoryFrequency(val freqMonths: Int) {
    DAILY(0),
    MONTHLY(1),
    QUARTERLY(3),
    ANNUAL(12),
}

// A category configured as an interleaved budget. `name` matches the stored category value
// (the SpendCategory enum name for built-ins, the raw name for custom categories).
data class InterleavedCategory(
    val name: String,
    val amount: BigDecimal,
    val frequency: CategoryFrequency,
    val anchorEpochDay: Long,
) {
    val anchor: LocalDate
        get() = LocalDate.ofEpochDay(anchorEpochDay)
}

// Minimal view of a transaction fed to the pure window math, so the engine never touches
// Room entities. `category` is the stored category column (null = uncategorized).
data class WindowSpend(
    val date: Date,
    val value: BigDecimal,
    val category: String?,
)

// Current window [start, end) for a scheduled category, or null for DAILY/none. Calendar
// month arithmetic (java.time clamps Jan 31 + 1 month -> Feb 28 and handles leap years).
fun windowFor(category: InterleavedCategory, today: LocalDate): Pair<LocalDate, LocalDate>? {
    if (category.frequency == CategoryFrequency.DAILY) return null
    val freqMonths = category.frequency.freqMonths
    val anchor = category.anchor
    val elapsedMonths = ChronoUnit.MONTHS.between(anchor, today).toInt()
    val windowsElapsed = if (elapsedMonths < 0) 0 else elapsedMonths / freqMonths
    val start = anchor.plusMonths(windowsElapsed.toLong() * freqMonths)
    val end = start.plusMonths(freqMonths.toLong())
    return start to end
}

// True when the recorded window start does not belong to the window containing `today`,
// i.e. the schedule rolled over. Pass a sentinel below any real epoch day to force a reset.
fun hasRolled(category: InterleavedCategory, today: LocalDate, recordedWindowStart: Long): Boolean {
    val window = windowFor(category, today) ?: return false
    return recordedWindowStart < window.first.toEpochDay() ||
        recordedWindowStart >= window.second.toEpochDay()
}

// Sum of spends whose date falls inside the current window and whose stored category
// matches the scheduled category (case-insensitive, like SpendCategory.fromStored).
fun windowSpent(
    transactions: List<WindowSpend>,
    category: InterleavedCategory,
    today: LocalDate,
): BigDecimal {
    val window = windowFor(category, today) ?: return BigDecimal.ZERO
    val start = window.first
    val end = window.second
    return transactions
        .filter { spend ->
            val date = spend.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            !date.isBefore(start) && date.isBefore(end) &&
                spend.category?.equals(category.name, ignoreCase = true) == true
        }
        .fold(BigDecimal.ZERO) { acc, spend -> acc + spend.value }
}

// The amount normalized to a monthly pace, used by the wallet daily-allowance stretch goal.
// DAILY has no window, so the cap amount is returned as-is.
fun monthlyEquivalent(category: InterleavedCategory): BigDecimal {
    if (category.frequency == CategoryFrequency.DAILY) return category.amount
    return category.amount.divide(
        category.frequency.freqMonths.toBigDecimal(),
        2,
        RoundingMode.HALF_EVEN,
    )
}

// Days left in the window including today, or 0 once the window is over.
fun daysLeftInWindow(category: InterleavedCategory, today: LocalDate): Int {
    val window = windowFor(category, today) ?: return 0
    return ChronoUnit.DAYS.between(today, window.second).toInt().coerceAtLeast(0)
}

// The day the window runs dry at the current pace (spent over elapsed days), or null when
// spending is zero or the window ends first.
fun projectedExhaustionDate(
    category: InterleavedCategory,
    today: LocalDate,
    spent: BigDecimal,
): LocalDate? {
    val window = windowFor(category, today) ?: return null
    if (spent <= BigDecimal.ZERO) return null
    val start = window.first
    val end = window.second
    val elapsedDays = ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(1)
    val pacePerDay = spent.divide(elapsedDays.toBigDecimal(), 6, RoundingMode.HALF_EVEN)
    if (pacePerDay <= BigDecimal.ZERO) return null
    val daysToExhaust =
        category.amount.divide(pacePerDay, 0, RoundingMode.CEILING).toInt().coerceAtLeast(1)
    val exhaustion = start.plusDays((daysToExhaust - 1).toLong())
    return if (!exhaustion.isBefore(end)) null else exhaustion
}
