package com.danilkinkin.buckwheat.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.di.roundValuesStoreKey
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.runBlocking
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
        runBlocking { context.settingsDataStore.edit { it.remove(roundValuesStoreKey) } }
        NumberDisplayConfig.roundValues = false
    }

    // The Application (Application.kt) syncs NumberDisplayConfig.roundValues from the
    // settings DataStore on a background scope, so a direct flag write can be clobbered
    // by that collector's initial false emission. Drive the flag through the real
    // DataStore instead and wait for the collector to apply it, making the test
    // deterministic.
    private fun setRoundValuesViaStore(enabled: Boolean) {
        runBlocking { context.settingsDataStore.edit { it[roundValuesStoreKey] = enabled } }

        val deadline = System.currentTimeMillis() + 5_000
        while (NumberDisplayConfig.roundValues != enabled && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
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
        setRoundValuesViaStore(true)

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
