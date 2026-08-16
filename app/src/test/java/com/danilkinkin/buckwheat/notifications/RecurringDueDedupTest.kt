package com.danilkinkin.buckwheat.notifications

import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.util.roundToDay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Calendar
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringDueDedupTest {

    private val today = roundToDay(Date())
    private val anotherDay = roundToDay(
        Calendar.getInstance().apply {
            time = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.time
    )

    private fun template(amount: String, comment: String = "Netflix") = RecurringTemplate(
        amount = BigDecimal(amount),
        comment = comment,
        dayOfMonth = 1,
    )

    private fun spend(
        amount: String,
        comment: String = "Netflix",
        date: Date = today,
    ) = Transaction(
        type = TransactionType.SPENT,
        value = BigDecimal(amount),
        date = date,
        comment = comment,
    )

    @Test
    fun keepsTemplatesWithoutMatchingSpend() {
        val templates = listOf(template("5000.00", "Rent"), template("999.99", "Internet"))
        assertEquals(templates, filterAlreadyRecorded(templates, emptyList(), today))
    }

    @Test
    fun dropsTemplateAlreadyRecordedOnSameDay() {
        val templates = listOf(template("999.99", "Internet"), template("5000.00", "Rent"))
        val result = filterAlreadyRecorded(templates, listOf(spend("999.99", "Internet")), today)
        assertEquals(listOf(template("5000.00", "Rent")), result)
    }

    @Test
    fun keepsTemplateWithDifferentAmount() {
        val templates = listOf(template("999.99", "Internet"))
        val result = filterAlreadyRecorded(templates, listOf(spend("99.99", "Internet")), today)
        assertEquals(templates, result)
    }

    @Test
    fun keepsTemplateRecordedOnAnotherDay() {
        val templates = listOf(template("999.99", "Internet"))
        val result = filterAlreadyRecorded(templates, listOf(spend("999.99", "Internet", anotherDay)), today)
        assertEquals(templates, result)
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<RecurringTemplate>(), filterAlreadyRecorded(emptyList(), emptyList(), today))
    }
}
