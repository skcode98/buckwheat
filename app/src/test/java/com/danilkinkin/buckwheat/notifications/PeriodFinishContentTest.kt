package com.danilkinkin.buckwheat.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
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
class PeriodFinishContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun periodEndAdjustsToLastSecondOfFinishDay() {
        val finishDate = Date.from(
            LocalDate.of(2026, 8, 20).atTime(10, 0)
                .atZone(ZoneId.systemDefault()).toInstant()
        )
        val end = PeriodFinishScheduler.periodEnd(finishDate)
        val expected = Date.from(
            LocalDate.of(2026, 8, 20).atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault()).toInstant()
        )
        assertEquals(expected.time, end.time)
    }

    @Test
    fun messageUsesFixedTitleAndLeftoverAmount() {
        val message = buildPeriodFinishMessage(
            context,
            BigDecimal("150.00"),
            ExtendCurrency.none(),
        )
        assertEquals("Period finished", message.title)
        assertEquals("Your budget period ended with 150 left over", message.text)
    }

    @Test
    fun messageReportsOverspendAsPositiveAmount() {
        val message = buildPeriodFinishMessage(
            context,
            BigDecimal("-50.00"),
            ExtendCurrency.none(),
        )
        assertEquals("Period finished", message.title)
        assertEquals("Your budget period ended over budget by 50", message.text)
    }

    @Test
    fun zeroRestUsesLeftoverVariant() {
        val message = buildPeriodFinishMessage(
            context,
            BigDecimal.ZERO,
            ExtendCurrency.none(),
        )
        assertEquals("Your budget period ended with 0 left over", message.text)
    }
}
