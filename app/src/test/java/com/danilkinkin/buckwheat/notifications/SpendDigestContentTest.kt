package com.danilkinkin.buckwheat.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpendDigestContentTest {
    private val today = LocalDate.of(2026, 8, 10)
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun transaction(day: LocalDate, value: String): Transaction = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(value),
        date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant()),
        comment = "test",
    )

    @Test
    fun weeklyDigestRangeCoversLastSevenDaysInclusive() {
        val (from, to) = digestRange(SpendDigestFrequency.WEEKLY, today)
        assertEquals(today.minusDays(6), from)
        assertEquals(today, to)
    }

    @Test
    fun monthlyDigestRangeCoversLastThirtyDaysInclusive() {
        val (from, to) = digestRange(SpendDigestFrequency.MONTHLY, today)
        assertEquals(today.minusDays(29), from)
        assertEquals(today, to)
    }

    @Test
    fun buildSpendDigestSumsAndCountsInRangeSpendsOnly() {
        val (from, to) = digestRange(SpendDigestFrequency.WEEKLY, today)
        val spends = listOf(
            transaction(today, "100"),
            transaction(today.minusDays(6), "50"),
            transaction(today.minusDays(7), "999"),
            transaction(today.plusDays(1), "888"),
        )
        val digest = buildSpendDigest(spends, from, to)
        assertEquals(BigDecimal("150"), digest.total)
        assertEquals(2, digest.transactionCount)
    }

    @Test
    fun buildSpendDigestDailyAverageIsTotalDividedByWindowDays() {
        val (from, to) = digestRange(SpendDigestFrequency.WEEKLY, today)
        val spends = listOf(
            transaction(today, "70"),
            transaction(today.minusDays(1), "70"),
        )
        val digest = buildSpendDigest(spends, from, to)
        assertEquals(BigDecimal("20.00"), digest.dailyAverage)
    }

    @Test
    fun buildSpendDigestIgnoresSpendsOutsideWindow() {
        val (from, to) = digestRange(SpendDigestFrequency.WEEKLY, today)
        val spends = listOf(
            transaction(today.minusDays(10), "100"),
            transaction(today.plusDays(2), "200"),
        )
        val digest = buildSpendDigest(spends, from, to)
        assertEquals(BigDecimal.ZERO, digest.total)
        assertEquals(0, digest.transactionCount)
        assertEquals(BigDecimal.ZERO, digest.dailyAverage)
    }

    @Test
    fun weeklyMessageUsesSevenDayTitleAndAverage() {
        val digest = SpendDigest(BigDecimal("150"), 3, BigDecimal("21.43"))
        val message = buildSpendDigestMessage(
            context,
            digest,
            SpendDigestFrequency.WEEKLY,
            ExtendCurrency.none(),
        )
        assertEquals("Last 7 days: 150", message.title)
        assertEquals("3 transactions — 21.43/day average", message.text)
    }

    @Test
    fun monthlyMessageUsesThirtyDayTitle() {
        val digest = SpendDigest(BigDecimal("900"), 12, BigDecimal("30.00"))
        val message = buildSpendDigestMessage(
            context,
            digest,
            SpendDigestFrequency.MONTHLY,
            ExtendCurrency.none(),
        )
        assertEquals("Last 30 days: 900", message.title)
    }
}
