package com.danilkinkin.buckwheat.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoExportSchedulerTest {
    @Test
    fun periodEndAdjustsToLastSecondOfFinishDay() {
        val finishDate = Date.from(
            LocalDate.of(2026, 8, 20).atTime(10, 0)
                .atZone(ZoneId.systemDefault()).toInstant()
        )
        val end = AutoExportScheduler.periodEnd(finishDate)
        val expected = Date.from(
            LocalDate.of(2026, 8, 20).atTime(23, 59, 59)
                .atZone(ZoneId.systemDefault()).toInstant()
        )
        assertEquals(expected.time, end.time)
    }
}