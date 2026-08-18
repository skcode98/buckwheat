package com.danilkinkin.buckwheat.data

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.base.balloon.BalloonController
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.di.TUTORS
import com.danilkinkin.buckwheat.di.TUTORIAL_STAGE
import com.danilkinkin.buckwheat.effects.ConfettiController
import dagger.hilt.android.lifecycle.HiltViewModel
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

    var sheetStates: MutableStateFlow<Map<String, PathState>> = MutableStateFlow(emptyMap())

    val isDebug: StateFlow<Boolean> = settingsRepository.isDebug()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val showSpentCardByDefault: StateFlow<Boolean> = settingsRepository.isShowSpentCardByDefault()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun getTutorialStage(name: TUTORS): StateFlow<TUTORIAL_STAGE> = settingsRepository.getTutorialStage(name)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TUTORIAL_STAGE.NONE)

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

    fun openSheet(state: PathState) {
        sheetStates.value = sheetStates.value.plus(Pair(state.name, state))
    }

    fun closeSheet(name: String) {
        sheetStates.value = sheetStates.value.minus(name)
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