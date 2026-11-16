package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The repository persists through DataStore, which writes on its own IO dispatcher. These tests
 * therefore drive the ViewModel with a real [Dispatchers.Unconfined] main dispatcher and wait for
 * observable state instead of relying on virtual time (the old `runTest` setup raced the DataStore
 * writes and deadlocked in teardown when resetting the main dispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockViewModelTest {

    private lateinit var context: Context
    private lateinit var repository: AppLockRepository
    private lateinit var viewModel: AppLockViewModel

    @Before
    fun setup() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        repository = AppLockRepository(context)
        context.settingsDataStore.edit { it.clear() }
        viewModel = AppLockViewModel(repository)
        // Wait for the initial refresh so every test starts from a known state.
        viewModel.awaitInitialized()
    }

    @After
    fun teardown() = runBlocking {
        context.settingsDataStore.edit { it.clear() }
        Dispatchers.resetMain()
    }

    private suspend fun awaitUntil(
        message: String,
        timeoutMs: Long = 10_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        fail(message)
    }

    /** Gives a launched-but-not-state-changing coroutine time to finish before asserting. */
    private fun settle(millis: Long = 300) {
        Thread.sleep(millis)
    }

    @Test
    fun initialState_noPin_notLocked() = runBlocking {
        viewModel.refresh()
        assertFalse(viewModel.isLocked)
        assertFalse(viewModel.hasPin)
        assertFalse(viewModel.biometricEnabled)
    }

    @Test
    fun refresh_setsHasPinWhenPinExists() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        viewModel.refresh()
        assertTrue(viewModel.hasPin)
    }

    @Test
    fun refresh_locksWhenEnabledAndHasPin() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun refresh_doesNotLockWhenDisabled() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(false)
        viewModel.refresh()
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun armLock_setsLastBackgroundTimeAndLocks() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.armLock()
        awaitUntil("view model did not lock after armLock()") { viewModel.isLocked }
        assertTrue(viewModel.isLocked)
        assertNotEquals(0L, repository.getLastBackgroundTime())
    }

    @Test
    fun armLock_doesNotLockWhenNoPin() = runBlocking {
        repository.setEnabled(true)
        viewModel.armLock()
        awaitUntil("armLock() did not record the background time") {
            repository.getLastBackgroundTime() != 0L
        }
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_skipsLockWithinWindow() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(true)
        repository.setSmartTimeoutSeconds(30)
        repository.setLastBackgroundTime(System.currentTimeMillis())

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        awaitUntil("smart timeout did not unlock the view model") { !viewModel.isLocked }
        assertFalse(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_locksWhenOutsideWindow() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(true)
        repository.setSmartTimeoutSeconds(10)
        repository.setLastBackgroundTime(System.currentTimeMillis() - 20_000)

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        settle()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun checkSmartTimeout_respectsDisabledFlag() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(false)
        repository.setLastBackgroundTime(System.currentTimeMillis())

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        settle()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun onPinChange_filtersNonDigits() = runBlocking {
        viewModel.onPinChange("12ab34")
        assertEquals("1234", viewModel.pinInput)
    }

    @Test
    fun onPinChange_capsAtMaxLength() = runBlocking {
        viewModel.onPinChange("123456789")
        assertEquals("12345678", viewModel.pinInput)
    }

    @Test
    fun verifyPin_wrongPin_incrementsAttempts() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()

        viewModel.onPinChange("0000")
        viewModel.verifyPin()

        awaitUntil("failed attempts were not incremented") {
            repository.getFailedAttempts() == 1 && viewModel.unlockError != null
        }
        assertEquals(1, repository.getFailedAttempts())
        assertNotNull(viewModel.unlockError)
    }

    @Test
    fun verifyPin_correctPin_unlocks() = runBlocking {
        val hash = com.danilkinkin.buckwheat.util.generatePinHash("1234")
        repository.setPinHash(hash)
        repository.setEnabled(true)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.onPinChange("1234")
        viewModel.verifyPin()

        awaitUntil("correct PIN did not unlock the view model") { !viewModel.isLocked }
        assertFalse(viewModel.isLocked)
        assertEquals(0, repository.getFailedAttempts())
        assertEquals(0L, repository.getLockoutUntil())
    }

    @Test
    fun verifyPin_lockoutBlocks() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setLockoutUntil(System.currentTimeMillis() + 60_000)
        viewModel.refresh()

        viewModel.onPinChange("0000")
        viewModel.verifyPin()

        awaitUntil("blocked PIN attempt did not clear the input") {
            viewModel.pinInput.isEmpty()
        }
        // Should not increment attempts (blocked by lockout)
        assertEquals(0, repository.getFailedAttempts())
    }

    @Test
    fun unlockWithBiometric_clearsLockout() = runBlocking {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setFailedAttempts(5)
        repository.setLockoutUntil(System.currentTimeMillis() + 60_000)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.unlockWithBiometric()

        awaitUntil("biometric unlock did not unlock the view model") { !viewModel.isLocked }
        assertFalse(viewModel.isLocked)
        assertEquals(0, repository.getFailedAttempts())
        assertEquals(0L, repository.getLockoutUntil())
    }

    @Test
    fun disableBiometric_clearsState() = runBlocking {
        // refresh() only reports biometricEnabled when a real AndroidKeyStore key exists, which a
        // JVM unit test cannot create, so exercise the clearing behaviour directly instead.
        repository.setBiometricEnabled(true)
        repository.setBiometricSecret("iv", "secret")

        viewModel.disableBiometric()

        awaitUntil("biometric state was not cleared") {
            !viewModel.biometricEnabled &&
                !repository.isBiometricEnabled().first() &&
                repository.getBiometricIv() == null &&
                repository.getBiometricSecret() == null
        }
        assertFalse(viewModel.biometricEnabled)
        assertFalse(repository.isBiometricEnabled().first())
        assertNull(repository.getBiometricIv())
        assertNull(repository.getBiometricSecret())
    }
}
