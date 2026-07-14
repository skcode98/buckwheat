package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.base.balloon.BalloonController
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.effects.ConfettiController
import com.danilkinkin.buckwheat.notifications.NotificationScheduler
import com.danilkinkin.buckwheat.notifications.NotificationType
import com.danilkinkin.buckwheat.reminder.ReminderManager
import com.danilkinkin.buckwheat.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SystemBarState (
    val statusBarColor: Color,
    val statusBarDarkIcons: Boolean,
    val navigationBarDarkIcons: Boolean,
    val navigationBarColor: Color,
)

data class PathState (
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
    val callback: (result: Map<String, Any?>) -> Unit = {},
)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val app: Context,
) : ViewModel() {

    var _snackbarHostState = SnackbarHostState()
        private set
    fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration =
            if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
        snackbarResult: (SnackbarResult) -> Unit = {},
    ) {
        viewModelScope.launch {
            _snackbarHostState.currentSnackbarData?.dismiss()

            val result = _snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration,
            )

            snackbarResult(result)
        }
    }

    var confettiController = ConfettiController()
        private set


    var balloonController = BalloonController()
        private set

    var topSheetDown: MutableState<Boolean> = mutableStateOf(false)

    var lockSwipeable: MutableState<Boolean> = mutableStateOf(false)

    var lockDraggable: MutableState<Boolean> = mutableStateOf(false)

    var showSystemKeyboard: MutableState<Boolean> = mutableStateOf(false)

    var statusBarStack: MutableList<() -> SystemBarState> = emptyList<() -> SystemBarState>().toMutableList()

    var sheetStates: MutableLiveData<Map<String, PathState>> = MutableLiveData(emptyMap())

    var isDebug = settingsRepository.isDebug().asLiveData()

    var showSpentCardByDefault = settingsRepository.isShowSpentCardByDefault().asLiveData()

    var persistTags = settingsRepository.isPersistTags().asLiveData()

    var reminderEnabled = settingsRepository.isReminderEnabled().asLiveData()
    var reminderHour = settingsRepository.getReminderHour().asLiveData()
    var reminderMinute = settingsRepository.getReminderMinute().asLiveData()

    var dailySpendOverviewEnabled = settingsRepository.isDailySpendOverviewEnabled().asLiveData()
    var weeklyOverviewEnabled = settingsRepository.isWeeklyOverviewEnabled().asLiveData()
    var monthlyExportEnabled = settingsRepository.isMonthlyExportEnabled().asLiveData()
    var monthlyOverviewEnabled = settingsRepository.isMonthlyOverviewEnabled().asLiveData()
    var factsInsightsEnabled = settingsRepository.isFactsInsightsEnabled().asLiveData()
    var goalsReminderEnabled = settingsRepository.isGoalsReminderEnabled().asLiveData()

    var syncEnabled = settingsRepository.isSyncEnabled().asLiveData()
    var syncHour = settingsRepository.getSyncHour().asLiveData()
    var syncMinute = settingsRepository.getSyncMinute().asLiveData()

    fun getTutorialStage(name: TUTORS) = settingsRepository.getTutorialStage(name).asLiveData()

    fun setShowSpentCardByDefault(showByDefault: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchShowSpentCardByDefault(showByDefault)
        }
    }

    fun setIsDebug(debug: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchDebug(debug)
        }
    }

    fun setPersistTags(persist: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchPersistTags(persist)
        }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchReminderEnabled(enabled)
            if (enabled) {
                val hour = settingsRepository.getReminderHour().first()
                val minute = settingsRepository.getReminderMinute().first()
                ReminderManager.schedule(app, hour, minute)
            } else {
                ReminderManager.cancel(app)
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setReminderTime(hour, minute)
            val enabled = settingsRepository.isReminderEnabled().first()
            if (enabled) {
                ReminderManager.schedule(app, hour, minute)
            }
        }
    }

    private suspend fun rescheduleNotification(type: NotificationType, enabled: Boolean) {
        if (enabled) {
            val hour = settingsRepository.getNotificationHour(type).first()
            val minute = settingsRepository.getNotificationMinute(type).first()
            when (type) {
                NotificationType.DAILY_SPEND_OVERVIEW ->
                    NotificationScheduler.scheduleDailyOverview(app, hour, minute)
                NotificationType.WEEKLY_OVERVIEW ->
                    NotificationScheduler.scheduleWeeklyOverview(app, hour, minute)
                NotificationType.MONTHLY_EXPORT ->
                    NotificationScheduler.scheduleMonthlyExport(app, hour, minute)
                NotificationType.MONTHLY_OVERVIEW ->
                    NotificationScheduler.scheduleMonthlyOverview(app, hour, minute)
                NotificationType.FACTS_INSIGHTS ->
                    NotificationScheduler.scheduleFactsInsights(app, hour, minute)
                NotificationType.GOALS_REMINDER ->
                    NotificationScheduler.scheduleGoalsReminder(app, hour, minute)
            }
        } else {
            NotificationScheduler.cancel(app, type)
        }
    }

    fun setDailySpendOverviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchDailySpendOverview(enabled)
            rescheduleNotification(NotificationType.DAILY_SPEND_OVERVIEW, enabled)
        }
    }

    fun setWeeklyOverviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchWeeklyOverview(enabled)
            rescheduleNotification(NotificationType.WEEKLY_OVERVIEW, enabled)
        }
    }

    fun setMonthlyExportEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchMonthlyExport(enabled)
            rescheduleNotification(NotificationType.MONTHLY_EXPORT, enabled)
        }
    }

    fun setMonthlyOverviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchMonthlyOverview(enabled)
            rescheduleNotification(NotificationType.MONTHLY_OVERVIEW, enabled)
        }
    }

    fun setFactsInsightsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchFactsInsights(enabled)
            rescheduleNotification(NotificationType.FACTS_INSIGHTS, enabled)
        }
    }

    fun setGoalsReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchGoalsReminder(enabled)
            rescheduleNotification(NotificationType.GOALS_REMINDER, enabled)
        }
    }

    fun setNotificationTime(type: NotificationType, hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setNotificationTime(type, hour, minute)
            val enabled = when (type) {
                NotificationType.DAILY_SPEND_OVERVIEW ->
                    settingsRepository.isDailySpendOverviewEnabled().first()
                NotificationType.WEEKLY_OVERVIEW ->
                    settingsRepository.isWeeklyOverviewEnabled().first()
                NotificationType.MONTHLY_EXPORT ->
                    settingsRepository.isMonthlyExportEnabled().first()
                NotificationType.MONTHLY_OVERVIEW ->
                    settingsRepository.isMonthlyOverviewEnabled().first()
                NotificationType.FACTS_INSIGHTS ->
                    settingsRepository.isFactsInsightsEnabled().first()
                NotificationType.GOALS_REMINDER ->
                    settingsRepository.isGoalsReminderEnabled().first()
            }
            if (enabled) {
                rescheduleNotification(type, true)
            }
        }
    }

    fun getNotificationHour(type: NotificationType) =
        settingsRepository.getNotificationHour(type).asLiveData()

    fun getNotificationMinute(type: NotificationType) =
        settingsRepository.getNotificationMinute(type).asLiveData()

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.switchSyncEnabled(enabled)
            if (enabled) {
                val hour = settingsRepository.getSyncHour().first()
                val minute = settingsRepository.getSyncMinute().first()
                SyncManager.schedule(app, hour, minute)
            } else {
                SyncManager.cancel(app)
            }
        }
    }

    fun setSyncTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setSyncTime(hour, minute)
            val enabled = settingsRepository.isSyncEnabled().first()
            if (enabled) {
                SyncManager.schedule(app, hour, minute)
            }
        }
    }

    fun openSheet(state: PathState) {
        sheetStates.value = mapOf(Pair(state.name, state))
    }

    fun closeSheet(name: String) {
        sheetStates.value = sheetStates.value?.minus(name) ?: emptyMap()
    }

    fun passTutorial(name: TUTORS) {
        viewModelScope.launch {
            settingsRepository.passTutorial(name)
        }
    }

    fun activateTutorial(name: TUTORS) {
        viewModelScope.launch {
            settingsRepository.activateTutorial(name)
        }
    }
}