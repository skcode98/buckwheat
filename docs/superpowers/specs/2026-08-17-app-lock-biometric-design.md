# App Lock & Biometric — Design Spec

> Full rewrite of the app lock and biometric authentication subsystem.
> Clean architecture with Repository pattern, smart timeout, proper layering, and comprehensive tests.

---

## 1. Goals

1. **Single write path** — all DataStore app lock writes go through `AppLockRepository`. No more direct `settingsDataStore.edit()` in UI code.
2. **Smart timeout** — configurable skip window so brief app switches don't require re-authentication.
3. **Biometric auto-fallback** — biometric failure falls back to PIN automatically. User can also tap to switch.
4. **Biometric enrollment change** — auto-disable + toast when fingerprint/face enrollment changes.
5. **Full test coverage** — unit tests for ViewModel, repository, and PIN hashing. No Compose UI tests.
6. **Clean state management** — ViewModel observes repository via Flow. No race conditions, no stale state.

---

## 2. Architecture

### 2.1 Data Flow

```
┌─────────────────┐
│  AppLockScreen  │──── calls ────> ┌──────────────────┐
│  (Lock UI)      │                 │  AppLockViewModel │
└─────────────────┘                 │  (UI State)      │
                                    └────────┬─────────┘
┌─────────────────┐                          │ observes
│ AppLockSetting  │──── calls ────> ┌────────┴─────────┐
│ (Settings UI)   │                 │ AppLockRepository │
└─────────────────┘                 │ (DataStore)       │
                                    └────────┬─────────┘
┌─────────────────┐                          │
│   MainActivity  │──── lifecycle ──>        │
│   (onStop/onStart)                         │
└─────────────────┘                 ┌────────┴─────────┐
                                    │     DataStore     │
                                    │ ("settings")      │
                                    └──────────────────┘
```

### 2.2 Component Responsibilities

| Component | Responsibility | State |
|-----------|---------------|-------|
| `AppLockRepository` | Single source of truth for all lock DataStore data. Exposes Flow-based state + suspend write methods. | **NEW** |
| `AppLockViewModel` | UI state holder. Observes repository. Provides actions (verifyPin, unlock, armLock, checkSmartTimeout). | **REWRITE** |
| `AppLockScreen` | Full-screen lock UI. PIN input + BiometricPrompt. Auto-fallback to PIN on biometric failure. | **REWRITE** |
| `AppLockSetting` | Settings UI. Uses repository for all writes. Adds smart timeout configuration. | **REWRITE** |
| `PinHash` | PBKDF2-HMAC-SHA256 hashing + lockout math. No changes to core logic. | **MINOR CLEANUP** |
| `AppLockBiometricKey` | AndroidKeyStore AES-GCM key management. No changes — solid implementation. | **KEEP** |
| `SettingsRepository` | Remove app lock methods (moved to AppLockRepository). | **MODIFY** |
| `MainActivity` | Use new ViewModel API. Add smart timeout check on `onStart()`. | **MODIFY** |

---

## 3. Feature Specifications

### 3.1 Smart Timeout

**Purpose:** Allow users to briefly switch apps (e.g., check a message) without re-entering PIN.

**Behavior:**
- When app goes to background (`onStop`), record timestamp + arm lock
- When app returns to foreground (`onStart`):
  - If `smartTimeoutEnabled && (now - lastBackgroundTime) < smartTimeoutSeconds` → **skip lock**
  - Otherwise → **show lock screen**
- Default: enabled, 30 seconds
- Configurable: OFF, 10s, 30s, 1min, 5min
- If user force-kills app → always lock on next open (timestamp is lost)

**DataStore keys:**
- `appLockSmartTimeoutEnabled` (Boolean, default true)
- `appLockSmartTimeoutSeconds` (Int, default 30)
- `appLockLastBackgroundTime` (Long, epoch millis)

### 3.2 Biometric Auto-Fallback

**Purpose:** Smooth UX when biometric fails — user sees PIN input immediately.

**Behavior:**
- Lock screen shows biometric button + PIN input
- Biometric auto-fires on composition (if available)
- If biometric fails (not enrolled, hardware error, lockout, user cancel):
  - Dismiss biometric prompt
  - Show PIN input (already visible)
  - User can tap fingerprint button to retry
- If biometric succeeds → decrypt secret → unlock
- If user taps "Use PIN" → hide fingerprint button, focus PIN field

### 3.3 Biometric Enrollment Change

**Purpose:** Detect when user adds/removes fingerprints and disable biometric unlock.

**Behavior:**
- `AppLockBiometricKey` key has `setInvalidatedByBiometricEnrollment(true)`
- When enrollment changes → key is automatically invalidated by Android OS
- On next `refreshLockState()`:
  - `AppLockBiometricKey.hasKey()` returns false (key gone)
  - Auto-disable biometric in DataStore
  - Show toast: "Biometric unlock disabled — fingerprint enrollment changed"
- User must re-enable biometric in Settings

### 3.4 PIN Setup/Change/Remove

**Setup flow:**
1. User taps "Set PIN" in Settings
2. Enter PIN (4-8 digits) → Confirm PIN
3. Generate PBKDF2 hash → Store in DataStore
4. Enable lock → `appLockEnabled = true`

**Change flow:**
1. User taps "Change PIN" in Settings
2. Enter current PIN → Enter new PIN → Confirm new PIN
3. Verify current hash → Generate new hash → Store
4. Auto-migrate legacy hash if present

**Remove flow:**
1. User taps "Remove PIN" in Settings
2. Confirm removal
3. Remove PIN hash, biometric data, enable flag from DataStore

### 3.5 Lockout Policy

**Current (keep):**
- 0 attempts → no lockout
- 1 failure → 30s lockout
- 2 failures → 60s lockout
- 3 failures → 120s lockout
- 4+ failures → 240s lockout (capped at 300s / 5min)
- Each failed attempt increments counter
- Successful PIN verify clears attempts and lockout

**Improvement:** Cancel previous lockout ticker coroutine before starting new one.

---

## 4. File-by-File Specification

### 4.1 `AppLockRepository.kt` (NEW — ~180 lines)

**Package:** `com.danilkinkin.buckwheat.data`

**Responsibility:** Single access point for all app lock DataStore operations.

```kotlin
class AppLockRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // --- Flows (observe) ---
    fun isAppLockEnabled(): Flow<Boolean>
    fun isBiometricEnabled(): Flow<Boolean>
    fun getSmartTimeoutEnabled(): Flow<Boolean>
    fun getSmartTimeoutSeconds(): Flow<Int>

    // --- Suspend reads ---
    suspend fun getPinHash(): String?
    suspend fun getFailedAttempts(): Int
    suspend fun getLockoutUntil(): Long
    suspend fun getLastBackgroundTime(): Long
    suspend fun getBiometricIv(): String?
    suspend fun getBiometricSecret(): String?

    // --- Suspend writes ---
    suspend fun setPinHash(hash: String?)
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setBiometricEnabled(enabled: Boolean)
    suspend fun setBiometricSecret(iv: String?, secret: String?)
    suspend fun setFailedAttempts(count: Int)
    suspend fun setLockoutUntil(until: Long)
    suspend fun setLastBackgroundTime(time: Long)
    suspend fun setSmartTimeoutEnabled(enabled: Boolean)
    suspend fun setSmartTimeoutSeconds(seconds: Int)

    // --- Composite operations ---
    suspend fun clearAll() // Remove all lock data (PIN + biometric + lockout)
}
```

**Key rules:**
- `setPinHash(null)` also removes biometric data (cascade)
- `setBiometricEnabled(false)` also removes IV/secret (cascade)
- `setEnabled(false)` also disables biometric flag (cascade)
- All methods are `suspend` (DataStore is async)

### 4.2 `AppLockViewModel.kt` (REWRITE — ~150 lines)

**Package:** `com.danilkinkin.buckwheat.data`

**Responsibility:** UI state holder. No DataStore access — only repository.

**State:**
```kotlin
// Compose state (UI reads these)
var isLocked: Boolean          // true = show lock screen
var hasPin: Boolean            // true = PIN is set
var biometricEnabled: Boolean  // true = biometric is configured
var pinInput: String           // current PIN input
var unlockError: String?       // error message
var lockoutSecondsLeft: Int    // countdown
var showBiometricButton: Boolean // true = show fingerprint button
```

**Actions:**
```kotlin
fun armLock()                  // Called from onStop()
fun checkSmartTimeout()        // Called from onStart()
fun onPinChange(pin: String)   // Update input
fun verifyPin()                // Verify and unlock
fun unlockWithBiometric()      // Called after biometric success
fun disableBiometric()         // Called on enrollment change
fun refresh()                  // Re-read all state from repository
```

**Smart timeout implementation:**
```kotlin
fun armLock() {
    viewModelScope.launch {
        repository.setLastBackgroundTime(System.currentTimeMillis())
        val enabled = repository.isAppLockEnabled().first()
        val hasPin = repository.getPinHash() != null
        if (enabled && hasPin) {
            isLocked = true
        }
    }
}

fun checkSmartTimeout() {
    viewModelScope.launch {
        val smartEnabled = repository.getSmartTimeoutEnabled().first()
        val timeout = repository.getSmartTimeoutSeconds().first()
        val lastBg = repository.getLastBackgroundTime()
        val elapsed = System.currentTimeMillis() - lastBg

        if (smartEnabled && elapsed < timeout * 1000L) {
            isLocked = false // Skip lock
        }
        // Otherwise keep current isLocked state (set by armLock or init)
    }
}
```

### 4.3 `AppLockScreen.kt` (REWRITE — ~200 lines)

**Package:** `com.danilkinkin.buckwheat.ui`

**Changes from current:**
- Add biometric error handling: on `ERROR_USER_CANCELED` / `ERROR_NEGATIVE_BUTTON` → don't disable biometric, just show PIN
- Add "Use PIN" text button to manually switch from biometric to PIN
- Keep `FLAG_SECURE` on window
- Keep `BiometricPrompt` auto-fire on composition

### 4.4 `AppLockSetting.kt` (REWRITE — ~400 lines)

**Package:** `com.danilkinkin.buckwheat.settings`

**Changes from current:**
- All DataStore writes go through `AppLockRepository` (no direct `settingsDataStore.edit`)
- Add smart timeout configuration row (Switch + dropdown for duration)
- Use `viewModel.refresh()` after PIN setup/change/remove

### 4.5 `SettingsRepository.kt` (MODIFY)

**Remove:** Lines 52-58 (app lock store keys), lines 288-373 (app lock methods)

**Add to `BackupRepository`:** Reference new `AppLockRepository` keys for backup exclusion.

### 4.6 `MainActivity.kt` (MODIFY — ~15 lines changed)

**Changes:**
- `onStop()`: call `appLockViewModel.armLock()` (same)
- `onStart()`: call `appLockViewModel.checkSmartTimeout()` (NEW)
- `setContent`: same conditional render

### 4.7 `PinHash.kt` (MINOR CLEANUP)

**Changes:**
- Extract `SecureRandom` to a shared instance
- No logic changes — PBKDF2 hashing and lockout math are correct

### 4.8 `AppLockBiometricKey.kt` (NO CHANGES)

Solid implementation. Keep as-is.

---

## 5. DataStore Keys

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `appLockEnabled` | Boolean | false | Master switch |
| `appLockPinHash` | String | — | PBKDF2 hash (format: `v1$salt$hash`) |
| `appLockBiometricEnabled` | Boolean | false | Biometric toggle |
| `appLockBiometricIv` | String | — | AES-GCM IV (Base64) |
| `appLockBiometricSecret` | String | — | AES-GCM ciphertext (Base64) |
| `appLockFailedAttempts` | Int | 0 | Failed PIN attempts |
| `appLockLockoutUntil` | Long | 0 | Epoch millis when lockout expires |
| `appLockSmartTimeoutEnabled` | Boolean | true | Smart timeout switch |
| `appLockSmartTimeoutSeconds` | Int | 30 | Skip window (seconds) |
| `appLockLastBackgroundTime` | Long | 0 | Epoch millis of last `onStop()` |

---

## 6. Permissions & Dependencies

### AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```
(Already present — no changes needed)

### build.gradle.kts
```kotlin
implementation("androidx.biometric:biometric:1.1.0")  // Already present
```

### No new dependencies required
All crypto uses standard JCE (`javax.crypto`, `java.security`).

---

## 7. Test Plan

### 7.1 `AppLockRepositoryTest.kt` (NEW — ~150 lines)

Tests for repository operations:
- Set/get pin hash
- Cascade: setPinHash(null) removes biometric data
- Cascade: setEnabled(false) disables biometric
- Cascade: setBiometricEnabled(false) removes IV/secret
- Failed attempts: increment, clear on success
- Lockout: set, read, clear
- Smart timeout: set/get enabled, set/get seconds, set/get last background time
- Clear all: removes all keys

### 7.2 `AppLockViewModelTest.kt` (NEW — ~200 lines)

Tests for ViewModel logic:
- `refreshLockState`: reads from repository, sets isLocked correctly
- `armLock`: sets lastBackgroundTime, sets isLocked
- `checkSmartTimeout`: skip lock if within window, lock if outside window
- `verifyPin`: correct PIN → unlock, wrong PIN → increment attempts, lockout
- `verifyPin`: lockout check — blocked during lockout
- `verifyPin`: legacy hash migration
- `unlockWithBiometric`: clears lockout, unlocks
- `disableBiometric`: clears biometric state
- Lockout ticker: counts down, stops when unlocked
- `onPinChange`: filters non-digits, caps length

### 7.3 `PinHashTest.kt` (EXISTING — 66 lines, keep as-is)

### 7.4 `AppLockPolicyTest.kt` (EXISTING — 33 lines, keep as-is)

---

## 8. Implementation Order

1. **AppLockRepository** — create + test
2. **AppLockViewModel** — rewrite + test
3. **AppLockScreen** — rewrite (biometric fallback, manual PIN switch)
4. **AppLockSetting** — rewrite (use repository, add smart timeout config)
5. **MainActivity** — modify (add smart timeout check)
6. **SettingsRepository** — remove app lock methods
7. **BackupRepository** — update key references
8. **PinHash** — minor cleanup (shared SecureRandom)
9. **Full integration test** — verify all flows work together

---

## 9. Migration Considerations

- **No database migration needed** — DataStore keys are unchanged (except 3 new ones)
- **Legacy PIN hash support** — `verifyPinHash()` already handles SHA-256 → PBKDF2 migration
- **Existing users** — their PIN + biometric data is preserved. New smart timeout keys have defaults.
- **Backup/restore** — `BackupRepository` must exclude new app lock keys from export
- **Biometric key invalidation** — existing users with biometric enabled may need to re-enable if their enrollment changed since last app version

---

## 10. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| KeyStore key lost after OS update | Biometric stops working | Auto-disable + toast, user falls back to PIN |
| DataStore corruption | All settings lost | App lock defaults to disabled, user must re-set up |
| Smart timeout bypassed by force-kill | Always locks | Timestamp lost on force-kill, correct behavior |
| Concurrent DataStore writes | Race condition | Repository serializes via `first()` before write |
| Multiple lockout tickers | Coroutine leak | Cancel previous ticker via Job before starting new |
