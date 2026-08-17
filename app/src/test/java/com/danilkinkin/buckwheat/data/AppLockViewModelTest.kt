package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var repository: AppLockRepository
    private lateinit var viewModel: AppLockViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        repository = AppLockRepository(context)
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }
        viewModel = AppLockViewModel(repository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        runTest {
            context.settingsDataStore.edit { it.clear() }
        }
    }

    @Test
    fun initialState_noPin_notLocked() = runTest {
        viewModel.refresh()
        assertFalse(viewModel.isLocked)
        assertFalse(viewModel.hasPin)
        assertFalse(viewModel.biometricEnabled)
    }

    @Test
    fun refresh_setsHasPinWhenPinExists() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        viewModel.refresh()
        assertTrue(viewModel.hasPin)
    }

    @Test
    fun refresh_locksWhenEnabledAndHasPin() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun refresh_doesNotLockWhenDisabled() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(false)
        viewModel.refresh()
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun armLock_setsLastBackgroundTimeAndLocks() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.armLock()
        assertTrue(viewModel.isLocked)
        assertNotEquals(0L, repository.getLastBackgroundTime())
    }

    @Test
    fun armLock_doesNotLockWhenNoPin() = runTest {
        repository.setEnabled(true)
        viewModel.armLock()
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_skipsLockWithinWindow() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(true)
        repository.setSmartTimeoutSeconds(30)
        repository.setLastBackgroundTime(System.currentTimeMillis())

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_locksWhenOutsideWindow() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(true)
        repository.setSmartTimeoutSeconds(10)
        repository.setLastBackgroundTime(System.currentTimeMillis() - 20_000)

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_respectsDisabledFlag() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(false)
        repository.setLastBackgroundTime(System.currentTimeMillis())

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun onPinChange_filtersNonDigits() = runTest {
        viewModel.onPinChange("12ab34")
        assertEquals("1234", viewModel.pinInput)
    }

    @Test
    fun onPinChange_capsAtMaxLength() = runTest {
        viewModel.onPinChange("123456789")
        assertEquals("12345678", viewModel.pinInput)
    }

    @Test
    fun verifyPin_wrongPin_incrementsAttempts() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()

        viewModel.onPinChange("0000")
        viewModel.verifyPin()

        assertEquals(1, repository.getFailedAttempts())
        assertNotNull(viewModel.unlockError)
    }

    @Test
    fun verifyPin_correctPin_unlocks() = runTest {
        val hash = com.danilkinkin.buckwheat.util.generatePinHash("1234")
        repository.setPinHash(hash)
        repository.setEnabled(true)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.onPinChange("1234")
        viewModel.verifyPin()

        assertFalse(viewModel.isLocked)
        assertEquals(0, repository.getFailedAttempts())
        assertEquals(0L, repository.getLockoutUntil())
    }

    @Test
    fun verifyPin_lockoutBlocks() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setLockoutUntil(System.currentTimeMillis() + 60_000)
        viewModel.refresh()

        viewModel.onPinChange("0000")
        viewModel.verifyPin()

        // Should not increment attempts (blocked by lockout)
        assertEquals(0, repository.getFailedAttempts())
    }

    @Test
    fun unlockWithBiometric_clearsLockout() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setFailedAttempts(5)
        repository.setLockoutUntil(System.currentTimeMillis() + 60_000)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.unlockWithBiometric()

        assertFalse(viewModel.isLocked)
        assertEquals(0, repository.getFailedAttempts())
        assertEquals(0L, repository.getLockoutUntil())
    }

    @Test
    fun disableBiometric_clearsState() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setBiometricEnabled(true)
        repository.setBiometricSecret("iv", "secret")
        viewModel.refresh()
        assertTrue(viewModel.biometricEnabled)

        viewModel.disableBiometric()

        assertFalse(viewModel.biometricEnabled)
        assertFalse(repository.isBiometricEnabled().first())
        assertNull(repository.getBiometricIv())
        assertNull(repository.getBiometricSecret())
    }
}
