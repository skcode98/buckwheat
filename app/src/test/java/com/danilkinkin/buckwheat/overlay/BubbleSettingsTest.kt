package com.danilkinkin.buckwheat.overlay

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BubbleSettingsTest {

    private fun tmpStore(): BubbleSettings {
        val file = File.createTempFile("bubble-settings", ".preferences_pb")
        file.deleteOnExit()
        return BubbleSettings(
            PreferenceDataStoreFactory.create { file },
        )
    }

    @Test
    fun `defaults to disabled with no saved position`() = runTest {
        val settings = tmpStore()
        assertFalse(settings.isEnabled().first())
        assertNull(settings.lastPosition().first())
    }

    @Test
    fun `persists enabled flag`() = runTest {
        val settings = tmpStore()
        settings.setEnabled(true)
        assertTrue(settings.isEnabled().first())
        settings.setEnabled(false)
        assertFalse(settings.isEnabled().first())
    }

    @Test
    fun `persists last position`() = runTest {
        val settings = tmpStore()
        settings.savePosition(12f, 340f)
        assertEquals(12f to 340f, settings.lastPosition().first())
    }
}
