package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val appLockEnabledStoreKey = booleanPreferencesKey("appLockEnabled")
val appLockPinHashStoreKey = stringPreferencesKey("appLockPinHash")
val appLockBiometricEnabledStoreKey = booleanPreferencesKey("appLockBiometricEnabled")
val appLockFailedAttemptsStoreKey = intPreferencesKey("appLockFailedAttempts")
val appLockLockoutUntilStoreKey = longPreferencesKey("appLockLockoutUntil")
val appLockBiometricIvStoreKey = stringPreferencesKey("appLockBiometricIv")
val appLockBiometricSecretStoreKey = stringPreferencesKey("appLockBiometricSecret")
val appLockSmartTimeoutEnabledStoreKey = booleanPreferencesKey("appLockSmartTimeoutEnabled")
val appLockSmartTimeoutSecondsStoreKey = intPreferencesKey("appLockSmartTimeoutSeconds")
val appLockLastBackgroundTimeStoreKey = longPreferencesKey("appLockLastBackgroundTime")

@Singleton
class AppLockRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.settingsDataStore

    // --- Flows ---

    fun isAppLockEnabled(): Flow<Boolean> = dataStore.data.map {
        it[appLockEnabledStoreKey] ?: false
    }

    fun isBiometricEnabled(): Flow<Boolean> = dataStore.data.map {
        it[appLockBiometricEnabledStoreKey] ?: false
    }

    fun getSmartTimeoutEnabled(): Flow<Boolean> = dataStore.data.map {
        it[appLockSmartTimeoutEnabledStoreKey] ?: true
    }

    fun getSmartTimeoutSeconds(): Flow<Int> = dataStore.data.map {
        it[appLockSmartTimeoutSecondsStoreKey] ?: 30
    }

    // --- Suspend reads ---

    suspend fun getPinHash(): String? =
        dataStore.data.first()[appLockPinHashStoreKey]

    suspend fun getFailedAttempts(): Int =
        dataStore.data.first()[appLockFailedAttemptsStoreKey] ?: 0

    suspend fun getLockoutUntil(): Long =
        dataStore.data.first()[appLockLockoutUntilStoreKey] ?: 0L

    suspend fun getLastBackgroundTime(): Long =
        dataStore.data.first()[appLockLastBackgroundTimeStoreKey] ?: 0L

    suspend fun getBiometricIv(): String? =
        dataStore.data.first()[appLockBiometricIvStoreKey]

    suspend fun getBiometricSecret(): String? =
        dataStore.data.first()[appLockBiometricSecretStoreKey]

    // --- Suspend writes ---

    suspend fun setPinHash(hash: String?) {
        dataStore.edit {
            if (hash.isNullOrBlank()) {
                it.remove(appLockPinHashStoreKey)
                it.remove(appLockBiometricEnabledStoreKey)
                it.remove(appLockBiometricIvStoreKey)
                it.remove(appLockBiometricSecretStoreKey)
            } else {
                it[appLockPinHashStoreKey] = hash
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit {
            it[appLockEnabledStoreKey] = enabled
            if (!enabled) {
                it[appLockBiometricEnabledStoreKey] = false
            }
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit {
            it[appLockBiometricEnabledStoreKey] = enabled
            if (!enabled) {
                it.remove(appLockBiometricIvStoreKey)
                it.remove(appLockBiometricSecretStoreKey)
            }
        }
    }

    suspend fun setBiometricSecret(iv: String?, secret: String?) {
        dataStore.edit {
            if (iv.isNullOrBlank() || secret.isNullOrBlank()) {
                it.remove(appLockBiometricIvStoreKey)
                it.remove(appLockBiometricSecretStoreKey)
            } else {
                it[appLockBiometricIvStoreKey] = iv
                it[appLockBiometricSecretStoreKey] = secret
            }
        }
    }

    suspend fun setFailedAttempts(count: Int) {
        dataStore.edit {
            if (count <= 0) {
                it.remove(appLockFailedAttemptsStoreKey)
            } else {
                it[appLockFailedAttemptsStoreKey] = count
            }
        }
    }

    suspend fun setLockoutUntil(until: Long) {
        dataStore.edit {
            if (until <= 0L) {
                it.remove(appLockLockoutUntilStoreKey)
            } else {
                it[appLockLockoutUntilStoreKey] = until
            }
        }
    }

    suspend fun setLastBackgroundTime(time: Long) {
        dataStore.edit {
            it[appLockLastBackgroundTimeStoreKey] = time
        }
    }

    suspend fun setSmartTimeoutEnabled(enabled: Boolean) {
        dataStore.edit {
            it[appLockSmartTimeoutEnabledStoreKey] = enabled
        }
    }

    suspend fun setSmartTimeoutSeconds(seconds: Int) {
        dataStore.edit {
            it[appLockSmartTimeoutSecondsStoreKey] = seconds
        }
    }

    suspend fun clearAll() {
        dataStore.edit {
            it.remove(appLockEnabledStoreKey)
            it.remove(appLockPinHashStoreKey)
            it.remove(appLockBiometricEnabledStoreKey)
            it.remove(appLockBiometricIvStoreKey)
            it.remove(appLockBiometricSecretStoreKey)
            it.remove(appLockFailedAttemptsStoreKey)
            it.remove(appLockLockoutUntilStoreKey)
            it.remove(appLockSmartTimeoutEnabledStoreKey)
            it.remove(appLockSmartTimeoutSecondsStoreKey)
            it.remove(appLockLastBackgroundTimeStoreKey)
        }
    }
}
