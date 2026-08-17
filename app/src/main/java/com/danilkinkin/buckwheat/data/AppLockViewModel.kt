package com.danilkinkin.buckwheat.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.util.APP_LOCK_PIN_MAX_LENGTH
import com.danilkinkin.buckwheat.util.AppLockBiometricKey
import com.danilkinkin.buckwheat.util.appLockLockoutMillis
import com.danilkinkin.buckwheat.util.generatePinHash
import com.danilkinkin.buckwheat.util.isLegacyPinHash
import com.danilkinkin.buckwheat.util.verifyPinHash
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val repository: AppLockRepository,
) : ViewModel() {
    var isLocked by mutableStateOf(false)
        private set
    var hasPin by mutableStateOf(false)
        private set
    var biometricEnabled by mutableStateOf(false)
        private set
    var pinInput by mutableStateOf("")
        private set
    var unlockError by mutableStateOf<String?>(null)
        private set
    var lockoutSecondsLeft by mutableStateOf(0)
        private set
    var biometricIv by mutableStateOf<String?>(null)
        private set
    var biometricSecret by mutableStateOf<String?>(null)
        private set
    var showBiometricButton by mutableStateOf(false)
        private set

    private var storedPinHash: String? = null
    private var lockoutUntilMillis = 0L
    private var lockoutTickerJob: Job? = null

    init {
        viewModelScope.launch {
            refresh()
        }
    }

    suspend fun refresh() {
        storedPinHash = repository.getPinHash()
        hasPin = storedPinHash != null
        biometricIv = repository.getBiometricIv()
        biometricSecret = repository.getBiometricSecret()
        val enroll = repository.isBiometricEnabled().first()
        biometricEnabled = enroll &&
            biometricIv != null &&
            biometricSecret != null &&
            AppLockBiometricKey.hasKey()
        showBiometricButton = biometricEnabled
        if (enroll && !biometricEnabled) {
            repository.setBiometricEnabled(false)
            repository.setBiometricSecret(null, null)
        }
        val enabled = repository.isAppLockEnabled().first()
        isLocked = enabled && hasPin
        lockoutUntilMillis = repository.getLockoutUntil()
        startLockoutTicker()
    }

    fun armLock() {
        viewModelScope.launch {
            repository.setLastBackgroundTime(System.currentTimeMillis())
            val hash = repository.getPinHash()
            storedPinHash = hash
            val enabled = repository.isAppLockEnabled().first()
            if (enabled && hash != null) {
                isLocked = true
            }
            lockoutUntilMillis = repository.getLockoutUntil()
            startLockoutTicker()
        }
    }

    fun checkSmartTimeout() {
        viewModelScope.launch {
            val smartEnabled = repository.getSmartTimeoutEnabled().first()
            val timeout = repository.getSmartTimeoutSeconds().first()
            val lastBg = repository.getLastBackgroundTime()
            val elapsed = System.currentTimeMillis() - lastBg

            if (smartEnabled && elapsed < timeout * 1000L) {
                isLocked = false
            }
        }
    }

    private fun startLockoutTicker() {
        lockoutTickerJob?.cancel()
        lockoutTickerJob = viewModelScope.launch {
            while (true) {
                if (!isLocked) return@launch
                val remaining = ((lockoutUntilMillis - System.currentTimeMillis()) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)
                lockoutSecondsLeft = remaining
                delay(1000)
            }
        }
    }

    fun onPinChange(value: String) {
        pinInput = value.filter { it.isDigit() }.take(APP_LOCK_PIN_MAX_LENGTH)
        unlockError = null
    }

    fun verifyPin() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (lockoutUntilMillis > now) {
                lockoutSecondsLeft = ((lockoutUntilMillis - now) / 1000L).toInt()
                pinInput = ""
                return@launch
            }

            val hash = storedPinHash
            if (hash != null && verifyPinHash(pinInput, hash)) {
                repository.setFailedAttempts(0)
                repository.setLockoutUntil(0L)
                lockoutUntilMillis = 0L
                lockoutSecondsLeft = 0
                if (isLegacyPinHash(hash)) {
                    storedPinHash = generatePinHash(pinInput)
                    repository.setPinHash(storedPinHash)
                }
                isLocked = false
                pinInput = ""
                unlockError = null
            } else {
                val attempts = repository.getFailedAttempts() + 1
                repository.setFailedAttempts(attempts)
                lockoutUntilMillis = now + appLockLockoutMillis(attempts)
                repository.setLockoutUntil(lockoutUntilMillis)
                lockoutSecondsLeft = (appLockLockoutMillis(attempts) / 1000L).toInt()
                unlockError = "Invalid PIN"
                pinInput = ""
            }
        }
    }

    fun unlockWithBiometric() {
        viewModelScope.launch {
            repository.setFailedAttempts(0)
            repository.setLockoutUntil(0L)
            lockoutUntilMillis = 0L
            lockoutSecondsLeft = 0
            isLocked = false
            pinInput = ""
            unlockError = null
        }
    }

    fun disableBiometric() {
        viewModelScope.launch {
            biometricEnabled = false
            showBiometricButton = false
            repository.setBiometricEnabled(false)
            repository.setBiometricSecret(null, null)
        }
    }

    fun hideBiometricButton() {
        showBiometricButton = false
    }
}
