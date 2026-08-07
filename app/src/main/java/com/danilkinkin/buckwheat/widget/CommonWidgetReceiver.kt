package com.danilkinkin.buckwheat.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.unit.ColorProvider
import androidx.lifecycle.asFlow
import com.danilkinkin.buckwheat.analytics.dailySpendTotals
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import javax.inject.Inject


@Composable
fun Color.toColorProvider(): ColorProvider {
    return try {
        val constructor = ColorProvider::class.java.getDeclaredConstructor(Color::class.java)
        constructor.isAccessible = true
        constructor.newInstance(this)
    } catch (e: Exception) {
        LocalContentColor.current
    }
}

val LocalContentColor = compositionLocalOf<ColorProvider> { throw Error("No set") }
val LocalAccentColor = compositionLocalOf<ColorProvider> { throw Error("No set") }

abstract class WidgetReceiver : GlanceAppWidgetReceiver() {

    enum class StateBudget {
        NOT_SET,
        END_PERIOD,
        NORMAL,
        NEW_DAILY,
        IS_OVER,
    }

    companion object {
        const val UPDATE_ACTION = "updateAction"

        val todayBudgetPreferenceKey = stringPreferencesKey("today-budget-key")
        val currencyPreferenceKey = stringPreferencesKey("currency-key")
        val stateBudgetPreferenceKey = stringPreferencesKey("state-budget-key")
        val spentPercentPreferenceKey = floatPreferencesKey("spent-percent-key")

        // Voice widget: comma-separated daily spends for the last 7 days (oldest first),
        // the daily budget used as the chart's goal-guard line, and transient input feedback.
        val voiceChartSeriesPreferenceKey = stringPreferencesKey("voice-chart-series-key")
        val voiceDailyBudgetPreferenceKey = stringPreferencesKey("voice-daily-budget-key")
        val voiceFeedbackStatePreferenceKey = stringPreferencesKey("voice-feedback-state-key")
        val voiceFeedbackTextPreferenceKey = stringPreferencesKey("voice-feedback-text-key")
        val voiceDesignPreferenceKey = stringPreferencesKey("voice-design-key")

        fun requestUpdateData(context: Context, receiverClass: Class<*>) {
            val intent = Intent(context, receiverClass)
            intent.action = UPDATE_ACTION
            context.sendBroadcast(intent)
        }
    }

    private val job = SupervisorJob()
    val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    @Inject
    lateinit var databaseRepository: SpendsRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)

        observeData(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == UPDATE_ACTION) {
            observeData(context)
        }
    }

    private fun whatBudgetForDay(
        finishDate: Date,
        spent: BigDecimal,
        budget: BigDecimal,
        dailyBudget: BigDecimal,
        spentFromDailyBudget: BigDecimal,
    ): BigDecimal {
        val restDays = countDaysToToday(finishDate) - 1
        val restBudget = (budget - spent) - dailyBudget
        val splitBudget = restBudget + dailyBudget - spentFromDailyBudget

        return splitBudget.divide(
            restDays.toBigDecimal().coerceAtLeast(BigDecimal.ONE),
            0,
            RoundingMode.FLOOR
        )
    }

    private fun observeData(context: Context) {
        coroutineScope.launch {

            val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(glanceAppWidget.javaClass)

            val finishDate = databaseRepository.getFinishPeriodDate().first()
            val actualFinishDate = databaseRepository.getFinishPeriodActualDate().first()
            val spentFromDailyBudget = databaseRepository.getSpentFromDailyBudget().first()
            val dailyBudget = databaseRepository.getDailyBudget().first()
            val spent = databaseRepository.getSpent().first()
            val budget = databaseRepository.getBudget().first()
            val currency = databaseRepository.getCurrency().first()
            val startPeriodDate = databaseRepository.getStartPeriodDate().first()
            val widgetDesign = settingsRepository.getVoiceWidgetDesign().first()

            val finishDateReached = finishDate !== null && finishDate.time <= Date().time
            val earlyFinishDateReached =
                actualFinishDate !== null && actualFinishDate.time <= Date().time

            if (
                finishDate === null
                || finishDateReached
                || earlyFinishDateReached
            ) {
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(
                        context = context,
                        definition = PreferencesGlanceStateDefinition,
                        glanceId = glanceId
                    ) { preferences ->
                        preferences.toMutablePreferences()
                            .apply {
                                this[stateBudgetPreferenceKey] =
                                    if (finishDateReached || earlyFinishDateReached) {
                                        StateBudget.END_PERIOD.name
                                    } else {
                                        StateBudget.NOT_SET.name
                                    }
                            }
                    }

                    glanceAppWidget.update(context, glanceId)
                }
            } else {
                val newBudget = dailyBudget - spentFromDailyBudget

                val newPerDayBudget = whatBudgetForDay(
                    finishDate = finishDate,
                    spent = spent,
                    budget = budget,
                    dailyBudget = dailyBudget,
                    spentFromDailyBudget = spentFromDailyBudget,
                )

                val endBudget = newPerDayBudget <= BigDecimal.ZERO

                val percent =
                    if (dailyBudget > BigDecimal.ZERO) (dailyBudget - spentFromDailyBudget).divide(
                        dailyBudget,
                        5,
                        RoundingMode.HALF_EVEN
                    ) else BigDecimal.ZERO

                val finalBudgetValue = if (newBudget >= BigDecimal.ZERO) {
                    newBudget
                } else {
                    newPerDayBudget.coerceAtLeast(BigDecimal.ZERO)
                }

                // Last-7-days spend window for the voice widget chart, clamped so a short
                // period never pulls in spends from before the period started.
                val today = Date()
                val windowStart = maxOf(
                    roundToDay(Date(today.time - 6 * DAY)),
                    roundToDay(startPeriodDate),
                )
                val chartSeries = dailySpendTotals(
                    spends = databaseRepository.getSpendsInRange(windowStart, today).asFlow().first(),
                    startDate = windowStart,
                    finishDate = today,
                )

                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(
                        context = context,
                        definition = PreferencesGlanceStateDefinition,
                        glanceId = glanceId
                    ) { preferences ->
                        preferences.toMutablePreferences()
                            .apply {
                                this[todayBudgetPreferenceKey] = finalBudgetValue.toString()
                                this[currencyPreferenceKey] = currency.value.toString()
                                this[stateBudgetPreferenceKey] =
                                    if (newBudget >= BigDecimal.ZERO) {
                                        StateBudget.NORMAL.name
                                    } else if (endBudget) {
                                        StateBudget.IS_OVER.name
                                    } else {
                                        StateBudget.NEW_DAILY.name
                                    }
                                this[spentPercentPreferenceKey] = percent.toFloat()
                                this[voiceChartSeriesPreferenceKey] =
                                    chartSeries.joinToString(",") { it.toPlainString() }
                                this[voiceDailyBudgetPreferenceKey] = dailyBudget.toPlainString()
                                this[voiceDesignPreferenceKey] = widgetDesign.name
                            }
                    }

                    glanceAppWidget.update(context, glanceId)
                }
            }

        }
    }
}
