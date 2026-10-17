package com.danilkinkin.buckwheat.overlay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val bubbleOverlayEnabledStoreKey = booleanPreferencesKey("bubbleOverlayEnabled")
val bubbleLastXStoreKey = floatPreferencesKey("bubbleLastX")
val bubbleLastYStoreKey = floatPreferencesKey("bubbleLastY")

// Capability flag ("user wants the bubble feature available") + remembered on-screen
// position. `enabled` deliberately does NOT mean "currently visible": dismissing the
// bubble hides it without flipping the flag; the flag is the Settings kill-switch.
class BubbleSettings(private val dataStore: DataStore<Preferences>) {

    fun isEnabled(): Flow<Boolean> =
        dataStore.data.map { it[bubbleOverlayEnabledStoreKey] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[bubbleOverlayEnabledStoreKey] = enabled }
    }

    fun lastPosition(): Flow<Pair<Float, Float>?> = dataStore.data.map { prefs ->
        val x = prefs[bubbleLastXStoreKey]
        val y = prefs[bubbleLastYStoreKey]
        if (x == null || y == null) null else x to y
    }

    suspend fun savePosition(x: Float, y: Float) {
        dataStore.edit {
            it[bubbleLastXStoreKey] = x
            it[bubbleLastYStoreKey] = y
        }
    }
}

fun bubbleSettings(context: Context): BubbleSettings = BubbleSettings(context.settingsDataStore)
