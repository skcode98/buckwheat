package com.danilkinkin.buckwheat.di

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class AiIntelligenceGateTest {

    @Test
    fun defaultsToEnabledWhenUnset() {
        assertEquals(true, aiIntelligenceEnabled(preferencesOf()))
    }

    @Test
    fun respectsExplicitFalse() {
        assertEquals(false, aiIntelligenceEnabled(preferencesOf(aiIntelligenceEnabledStoreKey to false)))
    }

    @Test
    fun respectsExplicitTrue() {
        assertEquals(true, aiIntelligenceEnabled(preferencesOf(aiIntelligenceEnabledStoreKey to true)))
    }

    @Test
    fun unrelatedKeysDoNotAffectGate() {
        val prefs = preferencesOf(
            booleanPreferencesKey("unrelated") to true,
            aiIntelligenceEnabledStoreKey to false,
        )
        assertEquals(false, aiIntelligenceEnabled(prefs))
    }
}
