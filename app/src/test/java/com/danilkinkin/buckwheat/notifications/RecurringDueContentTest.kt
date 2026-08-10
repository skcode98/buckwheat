package com.danilkinkin.buckwheat.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.data.entities.RecurringTemplate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringDueContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val currency = ExtendCurrency.none()

    private fun format(value: BigDecimal): String {
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 0
        return formatter.format(value)
    }

    @Test
    fun singleTemplateWithComment() {
        val templates = listOf(
            RecurringTemplate(
                amount = BigDecimal("5000.00"),
                comment = "Rent",
                dayOfMonth = 1,
            )
        )

        assertEquals(
            "Rent — ${format(BigDecimal("5000.00"))}",
            buildRecurringDueText(context, templates, currency),
        )
    }

    @Test
    fun blankCommentShowsOnlyAmount() {
        val templates = listOf(
            RecurringTemplate(
                amount = BigDecimal("250.50"),
                comment = "",
                dayOfMonth = 15,
            )
        )

        assertEquals(
            format(BigDecimal("250.50")),
            buildRecurringDueText(context, templates, currency),
        )
    }

    @Test
    fun multipleTemplatesJoinedByNewLine() {
        val templates = listOf(
            RecurringTemplate(
                amount = BigDecimal("5000.00"),
                comment = "Rent",
                dayOfMonth = 1,
            ),
            RecurringTemplate(
                amount = BigDecimal("999.99"),
                comment = "Internet",
                dayOfMonth = 1,
            ),
        )

        assertEquals(
            "Rent — ${format(BigDecimal("5000.00"))}\n" +
                "Internet — ${format(BigDecimal("999.99"))}",
            buildRecurringDueText(context, templates, currency),
        )
    }

    @Test
    fun emptyListYieldsEmptyText() {
        assertEquals("", buildRecurringDueText(context, emptyList(), currency))
    }
}
