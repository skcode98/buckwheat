package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.interleaved.CategoryFrequency
import com.danilkinkin.buckwheat.interleaved.InterleavedCategory
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.util.toDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class InterleavedAllowanceTest {

    private lateinit var context: Context
    private lateinit var spendsRepository: SpendsRepository
    private lateinit var settingsRepository: SettingsRepository
    private val currentDateUseCase = FakeGetCurrentDateUseCase()

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
            FakeBudgetPeriodDao(),
            currentDateUseCase,
        )
        spendsRepository.setBudget(
            20000.toBigDecimal(),
            anchorDay.plusDays(9).toDate(),
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

    private suspend fun schedule(category: InterleavedCategory) {
        settingsRepository.setCategoryCapsAndSchedules(
            caps = mapOf(category.name to category.amount),
            schedules = mapOf(category.name to category),
        )
    }

    @Test
    fun `whatBudgetForDay without schedules is unchanged`() = runTest {
        assertEquals(BigDecimal("2000.00"), spendsRepository.whatBudgetForDay())
    }

    @Test
    fun `whatBudgetForDay is reduced by the scheduled daily pace`() = runTest {
        schedule(
            InterleavedCategory(
                "FOOD", BigDecimal("6000"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
            )
        )

        // dailyPace = 6000 / 30 = 200 -> 2000 - 200 = 1800
        assertEquals(BigDecimal("1800.00"), spendsRepository.whatBudgetForDay())
    }

    @Test
    fun `nextDayBudget is reduced by the scheduled daily pace`() = runTest {
        schedule(
            InterleavedCategory(
                "FOOD", BigDecimal("6000"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
            )
        )

        assertEquals(BigDecimal("1800.00"), spendsRepository.nextDayBudget())
    }

    @Test
    fun `daily schedule reserves its cap amount`() = runTest {
        schedule(
            InterleavedCategory(
                "FOOD", BigDecimal("200"), CategoryFrequency.DAILY, anchorDay.toEpochDay(),
            )
        )

        assertEquals(BigDecimal("1800.00"), spendsRepository.whatBudgetForDay())
    }

    @Test
    fun `allowance clamps when schedules exceed 20 percent of the budget`() = runTest {
        spendsRepository.setBudget(
            5000.toBigDecimal(),
            anchorDay.plusDays(9).toDate(),
        )
        schedule(
            InterleavedCategory(
                "FOOD", BigDecimal("6000"), CategoryFrequency.MONTHLY, anchorDay.toEpochDay(),
            )
        )

        // raw 500, allowance 200 -> 300 would exceed the 20% floor (400), so it clamps to 400
        assertEquals(BigDecimal("400.00"), spendsRepository.whatBudgetForDay())
    }
}
