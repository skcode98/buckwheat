# App Lock & Biometric Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full rewrite of app lock + biometric with Repository pattern, smart timeout, biometric auto-fallback, and comprehensive tests.

**Architecture:** Single write path via `AppLockRepository`. ViewModel observes repository via Flow. Lock screen shows biometric + PIN with auto-fallback. Smart timeout allows brief app switches without re-auth.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, AndroidX Biometric 1.1.0, AndroidKeyStore (AES-256-GCM), PBKDF2-HMAC-SHA256, Hilt, JUnit 4 + Robolectric (tests)

**Spec:** `docs/superpowers/specs/2026-08-17-app-lock-biometric-design.md`

## Global Constraints

- Min SDK: (check `build.gradle.kts`)
- Target SDK: (check `build.gradle.kts`)
- Kotlin: (check `build.gradle.kts`)
- Compose: BOM-based
- DataStore: `androidx.datastore.preferences`
- Biometric: `androidx.biometric:biometric:1.1.0`
- Testing: JUnit 4, Robolectric 4.x, kotlinx-coroutines-test
- No Compose UI tests (too slow for Robolectric)
- All DataStore writes go through `AppLockRepository` — no direct `settingsDataStore.edit()` in UI code

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/main/java/com/danilkinkin/buckwheat/data/AppLockRepository.kt` | CREATE | Single DataStore access point |
| `app/src/main/java/com/danilkinkin/buckwheat/data/AppLockViewModel.kt` | REWRITE | UI state, observe repository, actions |
| `app/src/main/java/com/danilkinkin/buckwheat/ui/AppLockScreen.kt` | REWRITE | Lock screen: biometric + PIN, auto-fallback |
| `app/src/main/java/com/danilkinkin/buckwheat/settings/AppLockSetting.kt` | REWRITE | Settings: PIN management, smart timeout config |
| `app/src/main/java/com/danilkinkin/buckwheat/util/PinHash.kt` | MINOR | Extract shared SecureRandom instance |
| `app/src/main/java/com/danilkinkin/buckwheat/util/AppLockBiometricKey.kt` | KEEP | No changes |
| `app/src/main/java/com/danilkinkin/buckwheat/di/SettingsRepository.kt` | MODIFY | Remove app lock methods (lines 52-58, 288-373) |
| `app/src/main/java/com/danilkinkin/buckwheat/di/BackupRepository.kt` | MODIFY | Update app lock key references to use AppLockRepository |
| `app/src/main/java/com/danilkinkin/buckwheat/MainActivity.kt` | MODIFY | Add `checkSmartTimeout()` on `onStart()` |
| `app/src/test/java/com/danilkinkin/buckwheat/data/AppLockRepositoryTest.kt` | CREATE | Repository unit tests |
| `app/src/test/java/com/danilkinkin/buckwheat/data/AppLockViewModelTest.kt` | CREATE | ViewModel unit tests |
| `app/src/main/res/values/strings.xml` | MODIFY | Add smart timeout strings |

---

### Task 1: Create AppLockRepository

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/data/AppLockRepository.kt`
- Create: `app/src/test/java/com/danilkinkin/buckwheat/data/AppLockRepositoryTest.kt`
- Modify: `app/src/main/AndroidManifest.xml` (no change needed — `USE_BIOMETRIC` already declared)

**Interfaces:**
- Consumes: `context.settingsDataStore` (DataStore<Preferences>)
- Produces: `AppLockRepository` — all DataStore operations for app lock

- [ ] **Step 1: Create `AppLockRepository.kt`**

```kotlin
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

// Store keys — moved from SettingsRepository
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

    // --- Composite operations ---

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
```

- [ ] **Step 2: Create `AppLockRepositoryTest.kt`**

```kotlin
package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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
    fun `setPinHash stores hash`() = runTest {
        repo.setPinHash("v1\$salt\$hash")
        assertEquals("v1\$salt\$hash", repo.getPinHash())
    }

    @Test
    fun `setPinHash null removes hash and biometric data`() = runTest {
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
    fun `setEnabled cascade disables biometric`() = runTest {
        repo.setBiometricEnabled(true)
        repo.setEnabled(false)

        assertFalse(repo.isBiometricEnabled().first())
    }

    @Test
    fun `setBiometricEnabled false removes IV and secret`() = runTest {
        repo.setBiometricSecret("iv", "secret")
        repo.setBiometricEnabled(false)

        assertNull(repo.getBiometricIv())
        assertNull(repo.getBiometricSecret())
    }

    @Test
    fun `failed attempts round trip`() = runTest {
        assertEquals(0, repo.getFailedAttempts())
        repo.setFailedAttempts(3)
        assertEquals(3, repo.getFailedAttempts())
        repo.setFailedAttempts(0)
        assertEquals(0, repo.getFailedAttempts())
    }

    @Test
    fun `lockout round trip`() = runTest {
        assertEquals(0L, repo.getLockoutUntil())
        repo.setLockoutUntil(12345L)
        assertEquals(12345L, repo.getLockoutUntil())
        repo.setLockoutUntil(0L)
        assertEquals(0L, repo.getLockoutUntil())
    }

    @Test
    fun `smart timeout defaults`() = runTest {
        assertTrue(repo.getSmartTimeoutEnabled().first())
        assertEquals(30, repo.getSmartTimeoutSeconds().first())
    }

    @Test
    fun `smart timeout round trip`() = runTest {
        repo.setSmartTimeoutEnabled(false)
        repo.setSmartTimeoutSeconds(60)

        assertFalse(repo.getSmartTimeoutEnabled().first())
        assertEquals(60, repo.getSmartTimeoutSeconds().first())
    }

    @Test
    fun `clearAll removes everything`() = runTest {
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
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.danilkinkin.buckwheat.data.AppLockRepositoryTest"`
Expected: 9 tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/data/AppLockRepository.kt \
        app/src/test/java/com/danilkinkin/buckwheat/data/AppLockRepositoryTest.kt
git commit -m "feat(applock): add AppLockRepository with DataStore access"
```

---

### Task 2: Rewrite AppLockViewModel

**Files:**
- Rewrite: `app/src/main/java/com/danilkinkin/buckwheat/data/AppLockViewModel.kt`
- Create: `app/src/test/java/com/danilkinkin/buckwheat/data/AppLockViewModelTest.kt`

**Interfaces:**
- Consumes: `AppLockRepository`, `PinHash.kt`, `AppLockBiometricKey`
- Produces: `AppLockViewModel` with Compose state + actions

- [ ] **Step 1: Create `AppLockViewModelTest.kt`**

```kotlin
package com.danilkinkin.buckwheat.data

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.danilkinkin.buckwheat.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockViewModelTest {

    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

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
    fun `initial state - no pin, not locked`() = runTest {
        viewModel.refresh()
        assertFalse(viewModel.isLocked)
        assertFalse(viewModel.hasPin)
        assertFalse(viewModel.biometricEnabled)
    }

    @Test
    fun `refresh sets hasPin when pin exists`() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        viewModel.refresh()
        assertTrue(viewModel.hasPin)
    }

    @Test
    fun `refresh locks when enabled and has pin`() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()
        assertTrue(viewModel.isLocked)
    }

    @Test
    fun `armLock sets lastBackgroundTime and locks`() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.armLock()
        assertTrue(viewModel.isLocked)
        assertNotEquals(0L, repository.getLastBackgroundTime())
    }

    @Test
    fun `checkSmartTimeout skips lock within window`() = runTest {
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
    fun `checkSmartTimeout locks when outside window`() = runTest {
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
    fun `checkSmartTimeout respects disabled flag`() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        repository.setSmartTimeoutEnabled(false)
        repository.setLastBackgroundTime(System.currentTimeMillis())

        viewModel.refresh()
        assertTrue(viewModel.isLocked)

        viewModel.checkSmartTimeout()
        assertTrue(viewModel.isLocked) // Not skipped because smart timeout is off
    }

    @Test
    fun `onPinChange filters non-digits`() = runTest {
        viewModel.onPinChange("12ab34")
        assertEquals("1234", viewModel.pinInput)
    }

    @Test
    fun `onPinChange caps at max length`() = runTest {
        viewModel.onPinChange("123456789")
        assertEquals("12345678", viewModel.pinInput)
    }

    @Test
    fun `verifyPin wrong pin increments attempts`() = runTest {
        repository.setPinHash("v1\$salt\$hash")
        repository.setEnabled(true)
        viewModel.refresh()

        viewModel.onPinChange("0000")
        viewModel.verifyPin()

        assertEquals(1, repository.getFailedAttempts())
        assertNotNull(viewModel.unlockError)
    }

    @Test
    fun `unlockWithBiometric clears lockout`() = runTest {
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
}
```

- [ ] **Step 2: Run tests to verify they FAIL (no implementation yet)**

Run: `./gradlew testDebugUnitTest --tests "com.danilkinkin.buckwheat.data.AppLockViewModelTest"`
Expected: FAIL (AppLockViewModel doesn't match new API yet)

- [ ] **Step 3: Rewrite `AppLockViewModel.kt`**

Replace entire file with new implementation (see spec section 4.2 for architecture, Task 1 for AppLockRepository API). Key changes:
- Constructor takes `AppLockRepository` instead of `SettingsRepository`
- `refreshLockState()` becomes `refresh()` — reads from repository
- `armLock()` records lastBackgroundTime
- New `checkSmartTimeout()` method
- `verifyPin()` uses repository for attempts/lockout
- Lockout ticker uses `Job` and cancels previous before starting
- `unlockWithBiometric()` uses repository

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.danilkinkin.buckwheat.data.AppLockViewModelTest"`
Expected: 11 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/data/AppLockViewModel.kt \
        app/src/test/java/com/danilkinkin/buckwheat/data/AppLockViewModelTest.kt
git commit -m "feat(applock): rewrite ViewModel with repository pattern"
```

---

### Task 3: Update SettingsRepository + BackupRepository

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/SettingsRepository.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/BackupRepository.kt`

**Interfaces:**
- Consumes: (nothing new)
- Produces: SettingsRepository without app lock methods, BackupRepository with updated key references

- [ ] **Step 1: Remove app lock keys and methods from `SettingsRepository.kt`**

Remove:
- Lines 52-58: all `appLock*StoreKey` val declarations
- Lines 288-373: all app lock methods (`isAppLockEnabled`, `isAppLockBiometricEnabled`, `getAppLockPinHash`, `switchAppLockEnabled`, `setAppLockPinHash`, `switchAppLockBiometricEnabled`, `getAppLockFailedAttempts`, `setAppLockFailedAttempts`, `getAppLockLockoutUntil`, `setAppLockLockoutUntil`, `getAppLockBiometricIv`, `getAppLockBiometricSecret`, `setAppLockBiometricSecret`)

- [ ] **Step 2: Update `BackupRepository.kt`**

Replace references to old `appLock*StoreKey` in SettingsRepository with imports from `AppLockRepository.kt`. The backup exclusion logic should import the keys from the new location.

- [ ] **Step 3: Run full build to check for compile errors**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (or errors if any code still references old keys — fix those)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/di/SettingsRepository.kt \
        app/src/main/java/com/danilkinkin/buckwheat/di/BackupRepository.kt
git commit -m "refactor(applock): move app lock data to AppLockRepository"
```

---

### Task 4: Add Smart Timeout Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add smart timeout strings**

```xml
<!-- Smart timeout -->
<string name="app_lock_smart_timeout">Smart timeout</string>
<string name="app_lock_smart_timeout_desc">Skip lock when returning quickly</string>
<string name="app_lock_smart_timeout_off">Off</string>
<string name="app_lock_smart_timeout_10s">10 seconds</string>
<string name="app_lock_smart_timeout_30s">30 seconds</string>
<string name="app_lock_smart_timeout_1m">1 minute</string>
<string name="app_lock_smart_timeout_5m">5 minutes</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(applock): add smart timeout string resources"
```

---

### Task 5: Rewrite AppLockScreen

**Files:**
- Rewrite: `app/src/main/java/com/danilkinkin/buckwheat/ui/AppLockScreen.kt`

**Interfaces:**
- Consumes: `AppLockViewModel` (from Task 2)
- Produces: Full-screen lock UI with biometric auto-fallback + manual PIN switch

- [ ] **Step 1: Rewrite `AppLockScreen.kt`**

Key changes from current implementation:
1. Biometric `onAuthenticationError`: only disable on fatal errors, NOT on `ERROR_USER_CANCELED` / `ERROR_NEGATIVE_BUTTON` / `ERROR_CANCELED`
2. Add "Use PIN" text button below biometric button — sets `showBiometricButton = false`
3. When biometric fails (not cancelled), focus PIN field automatically
4. Keep `FLAG_SECURE` on window
5. Keep `BiometricPrompt` auto-fire on composition
6. ViewModel parameter: `viewModel: AppLockViewModel` (from new ViewModel)

- [ ] **Step 2: Run build to verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/ui/AppLockScreen.kt
git commit -m "feat(applock): rewrite lock screen with biometric auto-fallback"
```

---

### Task 6: Rewrite AppLockSetting

**Files:**
- Rewrite: `app/src/main/java/com/danilkinkin/buckwheat/settings/AppLockSetting.kt`

**Interfaces:**
- Consumes: `AppLockRepository`, `AppLockViewModel`, `AppLockBiometricKey`, `PinHash`
- Produces: Settings UI with PIN management + smart timeout config

- [ ] **Step 1: Rewrite `AppLockSetting.kt`**

Key changes from current implementation:
1. All DataStore writes go through `AppLockRepository` — zero direct `settingsDataStore.edit()` calls
2. After PIN setup/change/remove: call `viewModel.refresh()` so ViewModel immediately picks up new state
3. Add smart timeout configuration row:
   - Switch to enable/disable
   - Dropdown: Off / 10s / 30s / 1min / 5min
   - Uses `repository.setSmartTimeoutEnabled()` and `repository.setSmartTimeoutSeconds()`
4. Biometric toggle: on success, write IV/secret through repository
5. Biometric toggle: on cancel (not fatal error), don't revert toggle

- [ ] **Step 2: Run build to verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/settings/AppLockSetting.kt
git commit -m "feat(applock): rewrite settings with repository pattern and smart timeout"
```

---

### Task 7: Update MainActivity

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/MainActivity.kt`

**Interfaces:**
- Consumes: `AppLockViewModel` (from Task 2)
- Produces: Lifecycle-aware lock/unlock with smart timeout

- [ ] **Step 1: Update `MainActivity.kt`**

Changes:
1. `onStop()`: keep `appLockViewModel.armLock()` call (now records lastBackgroundTime)
2. `onStart()`: add `appLockViewModel.checkSmartTimeout()` call (NEW)
3. `setContent`: same conditional render (`if (isLocked) AppLockScreen else MainScreen`)
4. Ensure `AppLockViewModel` constructor matches new signature (Hilt will auto-resolve `AppLockRepository`)

- [ ] **Step 2: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/MainActivity.kt
git commit -m "feat(applock): add smart timeout check on app resume"
```

---

### Task 8: PinHash Cleanup

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/util/PinHash.kt`

- [ ] **Step 1: Extract shared `SecureRandom` instance**

Replace per-call `SecureRandom()` with a top-level `private val secureRandom = SecureRandom()`.

- [ ] **Step 2: Run existing tests to verify no regression**

Run: `./gradlew testDebugUnitTest --tests "com.danilkinkin.buckwheat.util.PinHashTest"`
Expected: All existing tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/util/PinHash.kt
git commit -m "chore(applock): extract shared SecureRandom in PinHash"
```

---

### Task 9: Final Integration Test

**Files:** No new files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS (existing + new AppLockRepositoryTest + AppLockViewModelTest)

- [ ] **Step 2: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run spotless format**

Run: `./gradlew spotlessApply`
Expected: Formatted

- [ ] **Step 4: Final commit with all formatting fixes**

```bash
git add -A
git commit -m "chore(applock): final formatting and cleanup"
```
