package com.danilkinkin.buckwheat.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import com.danilkinkin.buckwheat.data.RecurringAutoApplyMode
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecurringAutoApplyModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        runBlocking {
            context.settingsDataStore.edit { it.remove(recurringAutoApplyModeStoreKey) }
        }
    }

    @Test
    fun defaultsToSilent() = runTest {
        val mode = SettingsRepository(context).getRecurringAutoApplyMode().first()
        assertEquals(RecurringAutoApplyMode.SILENT, mode)
    }

    @Test
    fun persistsSelectedMode() = runTest {
        val repository = SettingsRepository(context)

        repository.setRecurringAutoApplyMode(RecurringAutoApplyMode.ASK)
        assertEquals(
            RecurringAutoApplyMode.ASK,
            repository.getRecurringAutoApplyMode().first(),
        )

        repository.setRecurringAutoApplyMode(RecurringAutoApplyMode.OFF)
        assertEquals(
            RecurringAutoApplyMode.OFF,
            repository.getRecurringAutoApplyMode().first(),
        )
    }
}
