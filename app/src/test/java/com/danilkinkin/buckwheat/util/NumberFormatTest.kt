package com.danilkinkin.buckwheat.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NumberFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val currency = ExtendCurrency.none()

    @Before
    fun setUp() {
        NumberDisplayConfig.roundValues = false
    }

    @After
    fun tearDown() {
        NumberDisplayConfig.roundValues = false
    }

    private fun expected(value: BigDecimal, maxFraction: Int, minFraction: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        formatter.maximumFractionDigits = maxFraction
        formatter.minimumFractionDigits = minFraction
        return formatter.format(value) + " "
    }

    @Test
    fun showsFractionalDigitsByDefault() {
        assertEquals(
            expected(BigDecimal("1234.50"), 2, 0),
            numberFormat(context, BigDecimal("1234.50"), currency),
        )
    }

    @Test
    fun roundsToWholeWhenRoundValuesEnabled() {
        NumberDisplayConfig.roundValues = true

        assertEquals(
            expected(BigDecimal("1234.50"), 0, 0),
            numberFormat(context, BigDecimal("1234.50"), currency),
        )
    }

    @Test
    fun forceShowAfterDotIgnoresRoundValues() {
        NumberDisplayConfig.roundValues = true

        assertEquals(
            expected(BigDecimal("1234.50"), 5, 1),
            numberFormat(context, BigDecimal("1234.50"), currency, forceShowAfterDot = true),
        )
    }

    @Test
    fun applyRoundValuesFalseKeepsDecimals() {
        NumberDisplayConfig.roundValues = true

        assertEquals(
            expected(BigDecimal("1234.50"), 2, 1),
            numberFormat(
                context,
                BigDecimal("1234.50"),
                currency,
                maximumFractionDigits = 2,
                minimumFractionDigits = 1,
                applyRoundValues = false,
            ),
        )
    }
}
