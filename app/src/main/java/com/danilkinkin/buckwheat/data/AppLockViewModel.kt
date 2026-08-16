package com.danilkinkin.buckwheat.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.SettingsRepository
import com.danilkinkin.buckwheat.util.APP_LOCK_PIN_MAX_LENGTH
import com.danilkinkin.buckwheat.util.AppLockBiometricKey
import com.danilkinkin.buckwheat.util.appLockLockoutMillis
import com.danilkinkin.buckwheat.util.generatePinHash
import com.danilkinkin.buckwheat.util.isLegacyPinHash
import com.danilkinkin.buckwheat.util.verifyPinHash
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    var isLocked by mutableStateOf(false)
        private set
    var hasPin by mutableStateOf(false)
        private set
    var biometricEnabled by mutableStateOf(false)
        private set
    var pinInput by mutableStateOf("")
        private set
    var unlockError by mutableStateOf(false)
        private set
    var lockoutSecondsLeft by mutableStateOf(0)
        private set
    var biometricIv by mutableStateOf<String?>(null)
        private set
    var biometricSecret by mutableStateOf<String?>(null)
        private set

    private var storedPinHash: String? = null
    private var lockoutUntilMillis = 0L

    init {
        viewModelScope.launch {
            refreshLockState()
        }
    }

    private suspend fun refreshLockState() {
        storedPinHash = settingsRepository.getAppLockPinHash()
        hasPin = storedPinHash != null
        biometricIv = settingsRepository.getAppLockBiometricIv()
        biometricSecret = settingsRepository.getAppLockBiometricSecret()
        // If the Keystore key is gone (data restore, key eviction) or was invalidated by a
        // biometric enrollment change, the stored secret is unrecoverable — disable biometric
        // unlock so the user is not shown a dead prompt.
        val enroll = settingsRepository.isAppLockBiometricEnabled().first()
        biometricEnabled = enroll &&
            biometricIv != null &&
            biometricSecret != null &&
            AppLockBiometricKey.hasKey()
        if (enroll && !biometricEnabled) {
            settingsRepository.switchAppLockBiometricEnabled(false)
            settingsRepository.setAppLockBiometricSecret(null, null)
        }
        val enabled = settingsRepository.isAppLockEnabled().first()
        isLocked = enabled && hasPin
        lockoutUntilMillis = settingsRepository.getAppLockLockoutUntil()
        startLockoutTicker()
    }

    // Re-arms the lock when the app leaves the foreground. Reads fresh settings so a PIN
    // set/removed during this session is respected.
    fun armLock() {
        viewModelScope.launch {
            val hash = settingsRepository.getAppLockPinHash()
            storedPinHash = hash
            val enabled = settingsRepository.isAppLockEnabled().first()
            if (enabled && hash != null) {
                isLocked = true
            }
            lockoutUntilMillis = settingsRepository.getAppLockLockoutUntil()
            startLockoutTicker()
        }
    }

    // Counts down the remaining lockout in the UI. Exits once the app is unlocked; re-armed by
    // refreshLockState()/armLock() whenever the lock goes up again.
    private fun startLockoutTicker() {
        viewModelScope.launch {
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
        unlockError = false
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
                settingsRepository.setAppLockFailedAttempts(0)
                settingsRepository.setAppLockLockoutUntil(0L)
                lockoutUntilMillis = 0L
                lockoutSecondsLeft = 0
                // Migrate hashes written by the legacy SHA-256 scheme to the PBKDF2 format.
                if (isLegacyPinHash(hash)) {
                    storedPinHash = generatePinHash(pinInput)
                    settingsRepository.setAppLockPinHash(storedPinHash)
                }
                isLocked = false
                pinInput = ""
                unlockError = false
            } else {
                val attempts = settingsRepository.getAppLockFailedAttempts() + 1
                settingsRepository.setAppLockFailedAttempts(attempts)
                lockoutUntilMillis = now + appLockLockoutMillis(attempts)
                settingsRepository.setAppLockLockoutUntil(lockoutUntilMillis)
                lockoutSecondsLeft = (appLockLockoutMillis(attempts) / 1000L).toInt()
                unlockError = true
                pinInput = ""
            }
        }
    }

    // Called only after the BiometricPrompt produced a CryptoObject whose decryption of the
    // unlock secret succeeded (i.e. a real OS-level biometric match released the Keystore key).
    fun unlockWithBiometric() {
        viewModelScope.launch {
            settingsRepository.setAppLockFailedAttempts(0)
            settingsRepository.setAppLockLockoutUntil(0L)
            lockoutUntilMillis = 0L
            lockoutSecondsLeft = 0
            isLocked = false
            pinInput = ""
            unlockError = false
        }
    }

    // The biometric path broke (missing/invalidated key or decryption failure). Fall back to
    // the PIN and drop the biometric flag so the UI stops offering a dead prompt.
    fun disableBiometric() {
        viewModelScope.launch {
            biometricEnabled = false
            settingsRepository.switchAppLockBiometricEnabled(false)
            settingsRepository.setAppLockBiometricSecret(null, null)
        }
    }
}
