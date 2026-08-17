package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: AppLockRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repo = AppLockRepository(context)
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }
    }

    @After
    fun teardown() {
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }
    }

    @Test
    fun setPinHash_storesHash() = runTest {
        repo.setPinHash("v1\$salt\$hash")
        assertEquals("v1\$salt\$hash", repo.getPinHash())
    }

    @Test
    fun setPinHash_null_removesHashAndBiometricData() = runTest {
        repo.setPinHash("v1\$salt\$hash")
        repo.setBiometricEnabled(true)
        repo.setBiometricSecret("iv", "secret")

        repo.setPinHash(null)

        assertNull(repo.getPinHash())
        assertFalse(repo.isBiometricEnabled().first())
        assertNull(repo.getBiometricIv())
        assertNull(repo.getBiometricSecret())
    }

    @Test
    fun setEnabled_cascade_disablesBiometric() = runTest {
        repo.setBiometricEnabled(true)
        repo.setEnabled(false)

        assertFalse(repo.isBiometricEnabled().first())
    }

    @Test
    fun setBiometricEnabled_false_removesIVAndSecret() = runTest {
        repo.setBiometricSecret("iv", "secret")
        repo.setBiometricEnabled(false)

        assertNull(repo.getBiometricIv())
        assertNull(repo.getBiometricSecret())
    }

    @Test
    fun failedAttempts_roundTrip() = runTest {
        assertEquals(0, repo.getFailedAttempts())
        repo.setFailedAttempts(3)
        assertEquals(3, repo.getFailedAttempts())
        repo.setFailedAttempts(0)
        assertEquals(0, repo.getFailedAttempts())
    }

    @Test
    fun lockout_roundTrip() = runTest {
        assertEquals(0L, repo.getLockoutUntil())
        repo.setLockoutUntil(12345L)
        assertEquals(12345L, repo.getLockoutUntil())
        repo.setLockoutUntil(0L)
        assertEquals(0L, repo.getLockoutUntil())
    }

    @Test
    fun smartTimeout_defaults() = runTest {
        assertTrue(repo.getSmartTimeoutEnabled().first())
        assertEquals(30, repo.getSmartTimeoutSeconds().first())
    }

    @Test
    fun smartTimeout_roundTrip() = runTest {
        repo.setSmartTimeoutEnabled(false)
        repo.setSmartTimeoutSeconds(60)

        assertFalse(repo.getSmartTimeoutEnabled().first())
        assertEquals(60, repo.getSmartTimeoutSeconds().first())
    }

    @Test
    fun clearAll_removesEverything() = runTest {
        repo.setPinHash("hash")
        repo.setBiometricEnabled(true)
        repo.setBiometricSecret("iv", "secret")
        repo.setFailedAttempts(5)
        repo.setLockoutUntil(99999L)
        repo.setLastBackgroundTime(11111L)

        repo.clearAll()

        assertNull(repo.getPinHash())
        assertFalse(repo.isAppLockEnabled().first())
        assertFalse(repo.isBiometricEnabled().first())
        assertEquals(0, repo.getFailedAttempts())
        assertEquals(0L, repo.getLockoutUntil())
        assertEquals(0L, repo.getLastBackgroundTime())
    }
}
