package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.toDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
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
class InterleavedRolloverTest {

    private lateinit var context: Context
    private lateinit var spendsRepository: SpendsRepository
    private lateinit var settingsRepository: SettingsRepository
    private val currentDateUseCase = FakeGetCurrentDateUseCase()
    private val budgetPeriodDao = FakeBudgetPeriodDao()

    private val anchorDay = LocalDate.of(2026, 9, 1)

    private fun dateOf(day: LocalDate): Date =
        Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())

    @Before
    fun init() = runTest {
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
        currentDateUseCase.value = dateOf(anchorDay)
        spendsRepository = SpendsRepository(
            context = context,
            FakeTransactionDao(),
            FakeSavedTagDao(),
            FakeSavedCategoryDao(),
            budgetPeriodDao,
            currentDateUseCase,
        )
        spendsRepository.setBudget(
            1000.toBigDecimal(),
            anchorDay.plusDays(60).toDate(),
        )
    }

    @After
    fun cleanup() = runTest {
        context.settingsDataStore.edit {
            it.remove(categoryCapsStoreKey)
            it.remove(categorySchedulesStoreKey)
            it.remove(categoryCapNotifiedStoreKey)
        }
    }

    private fun spend(value: String, day: LocalDate, category: String = "FOOD") =
        Transaction(TransactionType.SPENT, BigDecimal(value), day.toDate(), category = category)

    @Test
    fun `scheduled crossing records the window start`() = runTest {
        settingsRepository.setCategoryCapsAndSchedules(
            caps = mapOf("FOOD" to BigDecimal("100")),
            schedules = mapOf(
                "FOOD" to InterleavedCategory(
                    "FOOD", BigDecimal("100"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
                )
            ),
        )

        spendsRepository.addSpent(spend("100", anchorDay))

        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        assertEquals(2, notified["FOOD"]?.first)
        assertEquals(anchorDay.toEpochDay(), notified["FOOD"]?.second)
    }

    @Test
    fun `crossing a window boundary allows a second crossing to notify again`() = runTest {
        settingsRepository.setCategoryCapsAndSchedules(
            caps = mapOf("FOOD" to BigDecimal("100")),
            schedules = mapOf(
                "FOOD" to InterleavedCategory(
                    "FOOD", BigDecimal("100"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
                )
            ),
        )

        spendsRepository.addSpent(spend("100", anchorDay))
        val windowTwoStart = anchorDay.plusMonths(1)
        currentDateUseCase.value = dateOf(windowTwoStart.plusDays(1))
        spendsRepository.addSpent(spend("100", windowTwoStart.plusDays(1)))

        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        // The window rolled, so the bucket reset and the new window's crossing notified again.
        assertEquals(2, notified["FOOD"]?.first)
        assertEquals(windowTwoStart.toEpochDay(), notified["FOOD"]?.second)
    }

    @Test
    fun `new budget period keeps windowed entries but clears plain caps`() = runTest {
        settingsRepository.setCategoryCapsAndSchedules(
            caps = mapOf("FOOD" to BigDecimal("100")),
            schedules = mapOf(
                "FOOD" to InterleavedCategory(
                    "FOOD", BigDecimal("100"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
                )
            ),
        )
        spendsRepository.addSpent(spend("100", anchorDay))
        // Simulate a plain (unscheduled) category that already announced its crossing.
        context.settingsDataStore.edit {
            it[categoryCapNotifiedStoreKey] =
                "FOOD:2@${anchorDay.toEpochDay()};SHOPPING:1"
        }

        spendsRepository.setBudget(
            2000.toBigDecimal(),
            anchorDay.plusDays(30).toDate(),
        )

        val notified = parseCategoryCapNotifiedWithWindow(
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey]
        )
        assertEquals(2, notified["FOOD"]?.first)
        assertNull(notified["SHOPPING"])
    }

    @Test
    fun `daily schedule acts as a plain cap and clears on a new period`() = runTest {
        settingsRepository.setCategoryCapsAndSchedules(
            caps = mapOf("FOOD" to BigDecimal("100")),
            schedules = mapOf(
                "FOOD" to InterleavedCategory(
                    "FOOD", BigDecimal("100"), CategoryFrequency.DAILY, anchorDay.toEpochDay(),
                )
            ),
        )

        spendsRepository.addSpent(spend("100", anchorDay))
        // DAILY has no window: period-based progress, notified without a window suffix.
        assertEquals(
            "FOOD:2",
            context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey],
        )

        spendsRepository.setBudget(
            2000.toBigDecimal(),
            anchorDay.plusDays(30).toDate(),
        )
        assertNull(context.settingsDataStore.data.first()[categoryCapNotifiedStoreKey])
    }
}
