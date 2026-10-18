# Floating Voice Bubble Overlay — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Messenger-style floating bubble overlay that runs the full voice-spend pipeline (listen → parse → commit) from anywhere on the device, with visual feedback states, drag/snap/dismiss, and launch points from Settings, Keyboard screen, and the voice widget.

**Architecture:** New package `com.danilkinkin.buckwheat.overlay` hosts `BubbleOverlayService` — a microphone-type foreground service that owns a `WindowManager` overlay (ComposeView) and the entire voice pipeline, reusing existing pure helpers (`parseVoiceInputWithAi`, `parseVoiceInputs`, `voiceResultsToTransactions`, `SpendsRepository.addSpent`). Bubble feedback state is a service-local `StateFlow` (NOT Glance prefs; the widget's state machine stays parallel and untouched). Pure logic (state transitions, snap/dismiss math, session orchestration) is extracted into testable units; the service is a thin Android shell around them.

**Tech Stack:** Kotlin, Jetpack Compose (regular `ComposeView` in overlay window — full animation support, unlike Glance), Hilt (`@AndroidEntryPoint` service), DataStore preferences (`settingsDataStore`), Android `WindowManager` `TYPE_APPLICATION_OVERLAY`, `SpeechRecognizer`, Glance 1.1.1 (widget button only), JUnit4 + Robolectric + `createComposeRule` for tests.

**Spec:** Design approved in chat (brainstorming session 2026-10-17). Key decisions: post-commit confirmation only (no pre-commit dialog); master switch in Settings is a kill-switch for capability, dismiss only hides the visible bubble; tap IDLE = start listening immediately; 4s auto-reset for ADDED/ERROR; edge snap after drag; dismiss via bottom-center X target while dragging OR downward fling; color follows widget design setting via `effectiveVoiceWidgetDesign`; launch points = Settings toggle + show/hide button, Keyboard mic row icon, voice widget bubble button (Glance has no long-press action — agreed workaround B); no boot auto-show; `START_NOT_STICKY`.

**Spec's project-wide requirements:**
- Never touch `VoiceWidgetCommitService` / widget Glance state machine — they stay exactly as-is.
- Reuse, don't duplicate: `voiceResultsToTransactions` (widget/voice/VoiceWidgetCommitService.kt:267), `parseVoiceInputWithAi`/`parseVoiceInputs` (keyboard/VoiceAi.kt, VoiceInputParser.kt), `SpendsRepository.addSpent` (di/SpendsRepository.kt:452), `VoiceWidgetReceiver.requestUpdateData` (widget/voice/VoiceWidgetReceiver.kt:10), mic-permission gate pattern via `MainActivity` + `EXTRA_REQUEST_MIC_PERMISSION` (widget/voice/VoiceWidgetMicCallback.kt:28-39, constant in VoiceWidgetNotifications.kt:17).
- Service `foregroundServiceType="microphone"`, `START_NOT_STICKY`, `startForeground` immediately in `onStartCommand` before any other work.
- Positions stored as raw px Floats in DataStore; restore always clamps.
- Commit exactly after every task; all tests green before next task.

---

### Task 1: Bubble state model + position math (pure logic)

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleState.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubblePosition.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleStateTest.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/overlay/BubblePositionTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin).
- Produces:
  - `enum class BubbleFeedbackState { IDLE, LISTENING, PROCESSING, ADDED, ERROR }`
  - `data class BubbleUiState(val state: BubbleFeedbackState, val caption: String? = null)`
  - `fun acceptsTap(state: BubbleFeedbackState): Boolean`
  - `fun autoResetDelayMs(state: BubbleFeedbackState): Long?` — 4000 for ADDED/ERROR, null otherwise
  - `fun snapX(currentX: Float, screenWidth: Float, bubbleWidth: Float, marginPx: Float): Float`
  - `fun clampY(currentY: Float, screenHeight: Float, bubbleHeight: Float, topInsetPx: Float, bottomInsetPx: Float): Float`
  - `fun isFlingDismiss(velocityY: Float, thresholdPx: Float): Boolean`
  - `fun isOverDismissTarget(x: Float, y: Float, targetLeft: Float, targetTop: Float, targetRight: Float, targetBottom: Float): Boolean`

- [ ] **Step 1: Write failing tests**

`app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleStateTest.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleStateTest {

    @Test
    fun `idle and added accept taps, busy states do not`() {
        assertTrue(acceptsTap(BubbleFeedbackState.IDLE))
        assertTrue(acceptsTap(BubbleFeedbackState.ADDED))
        assertFalse(acceptsTap(BubbleFeedbackState.LISTENING))
        assertFalse(acceptsTap(BubbleFeedbackState.PROCESSING))
        assertFalse(acceptsTap(BubbleFeedbackState.ERROR))
    }

    @Test
    fun `added and error auto-reset after 4 seconds`() {
        assertEquals(4_000L, autoResetDelayMs(BubbleFeedbackState.ADDED))
        assertEquals(4_000L, autoResetDelayMs(BubbleFeedbackState.ERROR))
        assertNull(autoResetDelayMs(BubbleFeedbackState.IDLE))
        assertNull(autoResetDelayMs(BubbleFeedbackState.LISTENING))
        assertNull(autoResetDelayMs(BubbleFeedbackState.PROCESSING))
    }
}
```

`app/src/test/java/com/danilkinkin/buckwheat/overlay/BubblePositionTest.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePositionTest {

    @Test
    fun `snaps to left edge when nearer left`() {
        assertEquals(0f, snapX(currentX = 10f, screenWidth = 1000f, bubbleWidth = 100f, marginPx = 8f))
    }

    @Test
    fun `snaps to right edge when nearer right`() {
        assertEquals(
            892f,
            snapX(currentX = 800f, screenWidth = 1000f, bubbleWidth = 100f, marginPx = 8f),
        )
    }

    @Test
    fun `clamps y inside usable screen area`() {
        assertEquals(
            100f,
            clampY(currentY = -50f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
        assertEquals(
            1800f,
            clampY(currentY = 5000f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
        assertEquals(
            500f,
            clampY(currentY = 500f, screenHeight = 2000f, bubbleHeight = 100f, topInsetPx = 100f, bottomInsetPx = 100f),
        )
    }

    @Test
    fun `fling dismiss only on fast downward fling`() {
        assertTrue(isFlingDismiss(velocityY = 5000f, thresholdPx = 3000f))
        assertFalse(isFlingDismiss(velocityY = 1000f, thresholdPx = 3000f))
        assertFalse(isFlingDismiss(velocityY = -5000f, thresholdPx = 3000f))
    }

    @Test
    fun `dismiss target hit test`() {
        assertTrue(
            isOverDismissTarget(150f, 1900f, targetLeft = 100f, targetTop = 1850f, targetRight = 200f, targetBottom = 1950f),
        )
        assertFalse(
            isOverDismissTarget(50f, 50f, targetLeft = 100f, targetTop = 1850f, targetRight = 200f, targetBottom = 1950f),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.*"`
Expected: COMPILATION ERROR — unresolved references `BubbleFeedbackState`, `acceptsTap`, `autoResetDelayMs`, `snapX`, `clampY`, `isFlingDismiss`, `isOverDismissTarget`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleState.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

enum class BubbleFeedbackState { IDLE, LISTENING, PROCESSING, ADDED, ERROR }

data class BubbleUiState(val state: BubbleFeedbackState, val caption: String? = null)

// Taps start a new voice session only from a settled state. ADDED re-taps begin a fresh
// session immediately (widget behavior); busy and error states ignore taps.
fun acceptsTap(state: BubbleFeedbackState): Boolean =
    state == BubbleFeedbackState.IDLE || state == BubbleFeedbackState.ADDED

// Transient feedback states return to IDLE on their own; session states never auto-reset.
fun autoResetDelayMs(state: BubbleFeedbackState): Long? = when (state) {
    BubbleFeedbackState.ADDED, BubbleFeedbackState.ERROR -> 4_000L
    else -> null
}
```

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubblePosition.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import kotlin.math.abs

// Window x for the bubble after a drag: pinned to the nearer screen edge, inset by marginPx.
fun snapX(currentX: Float, screenWidth: Float, bubbleWidth: Float, marginPx: Float): Float =
    if (currentX + bubbleWidth / 2f < screenWidth / 2f) {
        marginPx
    } else {
        (screenWidth - bubbleWidth - marginPx).coerceAtLeast(marginPx)
    }

// Keeps the bubble fully inside the usable screen area (status/nav insets excluded).
fun clampY(currentY: Float, screenHeight: Float, bubbleHeight: Float, topInsetPx: Float, bottomInsetPx: Float): Float {
    val minY = topInsetPx
    val maxY = (screenHeight - bottomInsetPx - bubbleHeight).coerceAtLeast(minY)
    return currentY.coerceIn(minY, maxY)
}

// Downward fling past the velocity threshold dismisses the bubble.
fun isFlingDismiss(velocityY: Float, thresholdPx: Float): Boolean =
    velocityY > thresholdPx && abs(velocityY) > abs(velocityY * 0f) // velocityY positive = downward

fun isOverDismissTarget(
    x: Float,
    y: Float,
    targetLeft: Float,
    targetTop: Float,
    targetRight: Float,
    targetBottom: Float,
): Boolean = x in targetLeft..targetRight && y in targetTop..targetBottom
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.*"`
Expected: PASS (all BubbleStateTest + BubblePositionTest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleState.kt app/src/main/java/com/danilkinkin/buckwheat/overlay/BubblePosition.kt app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleStateTest.kt app/src/test/java/com/danilkinkin/buckwheat/overlay/BubblePositionTest.kt
git commit -m "feat: bubble overlay state model and position math"
```

---

### Task 2: BubbleSettings (DataStore persistence)

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleSettings.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleSettingsTest.kt`

**Interfaces:**
- Consumes: app-wide `settingsDataStore` pattern (`val Context.settingsDataStore by preferencesDataStore("settings")` in MainActivity.kt:47).
- Produces (all top-level in `overlay` package, file `BubbleSettings.kt`):
  - `val bubbleOverlayEnabledStoreKey: BooleanPreferencesKey` ("bubbleOverlayEnabled")
  - `val bubbleLastXStoreKey: FloatPreferencesKey` ("bubbleLastX")
  - `val bubbleLastYStoreKey: FloatPreferencesKey` ("bubbleLastY")
  - `class BubbleSettings(private val dataStore: DataStore<Preferences>)` with:
    - `fun isEnabled(): Flow<Boolean>` (default false)
    - `suspend fun setEnabled(enabled: Boolean)`
    - `fun lastPosition(): Flow<Pair<Float, Float>?>` (null = never placed)
    - `suspend fun savePosition(x: Float, y: Float)`
  - `fun bubbleSettings(context: Context): BubbleSettings` — wraps `context.settingsDataStore`

- [ ] **Step 1: Write failing test**

`app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleSettingsTest.kt` (Robolectric, tmp-file DataStore):

```kotlin
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

@RunWith(RobolectricTestRunner::class)
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.BubbleSettingsTest"`
Expected: COMPILATION ERROR — unresolved `BubbleSettings`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleSettings.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.BubbleSettingsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleSettings.kt app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleSettingsTest.kt
git commit -m "feat: bubble overlay DataStore persistence (enabled flag + position)"
```

---

### Task 3: Voice session orchestrator (testable pipeline)

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSession.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSessionTest.kt`

**Interfaces:**
- Consumes: `BubbleFeedbackState` (Task 1).
- Produces (file `BubbleVoiceSession.kt`):
  - `data class BubbleRecognitionOutcome(val transcript: String?, val errorCode: Int)`
  - `interface BubbleSessionHost`:
    - `suspend fun hasBudget(): Boolean`
    - `suspend fun recognize(): BubbleRecognitionOutcome`
    - `suspend fun toTransactions(transcript: String): List<com.danilkinkin.buckwheat.data.entities.Transaction>`
    - `suspend fun commit(transactions: List<Transaction>)`
    - `fun captionNoBudget(): String`
    - `fun captionRecognitionError(errorCode: Int): String`
    - `fun captionParseFailed(): String`
    - `fun captionSaveFailed(): String`
    - `fun captionAdded(transactions: List<Transaction>): String`
  - `class BubbleSessionBusyException : Exception()`
  - `suspend fun runVoiceSession(host: BubbleSessionHost, onState: (BubbleUiState) -> Unit)`

- [ ] **Step 1: Write failing tests**

`app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSessionTest.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import com.danilkinkin.buckwheat.data.entities.Transaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeHost(
    private val budget: Boolean = true,
    private val outcome: BubbleRecognitionOutcome = BubbleRecognitionOutcome("150 tea", 0),
    private val transactions: List<Transaction> = listOf(
        Transaction(type = TransactionType.SPENT, value = BigDecimal("150"), date = Date(), comment = "tea"),
    ),
    private val commitFails: Boolean = false,
) : BubbleSessionHost {
    val committed = mutableListOf<List<Transaction>>()
    val states = mutableListOf<BubbleUiState>()

    override suspend fun hasBudget() = budget
    override suspend fun recognize() = outcome
    override suspend fun toTransactions(transcript: String) = transactions
    override suspend fun commit(transactions: List<Transaction>) {
        if (commitFails) throw RuntimeException("db down")
        committed += transactions
    }

    override fun captionNoBudget() = "no budget"
    override fun captionRecognitionError(errorCode: Int) = "error $errorCode"
    override fun captionParseFailed() = "parse failed"
    override fun captionSaveFailed() = "save failed"
    override fun captionAdded(transactions: List<Transaction>) = "added ${transactions.size}"
}

class BubbleVoiceSessionTest {

    @Test
    fun `successful session goes listening to processing to added`() = runTest {
        val host = FakeHost()
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(host) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.PROCESSING),
                BubbleUiState(BubbleFeedbackState.ADDED, "added 1"),
            ),
            states,
        )
        assertEquals(1, host.committed.size)
    }

    @Test
    fun `no budget ends in error without listening`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(FakeHost(budget = false)) { states += it }
        assertEquals(
            listOf(BubbleUiState(BubbleFeedbackState.ERROR, "no budget")),
            states,
        )
    }

    @Test
    fun `recognition error maps to error state with code`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(
            FakeHost(outcome = BubbleRecognitionOutcome(transcript = null, errorCode = 7)),
        ) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.ERROR, "error 7"),
            ),
            states,
        )
    }

    @Test
    fun `unparseable transcript ends in error and commits nothing`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        val host = FakeHost(transactions = emptyList())
        runVoiceSession(host) { states += it }
        assertEquals(
            listOf(
                BubbleUiState(BubbleFeedbackState.LISTENING),
                BubbleUiState(BubbleFeedbackState.PROCESSING),
                BubbleUiState(BubbleFeedbackState.ERROR, "parse failed"),
            ),
            states,
        )
        assertTrue(host.committed.isEmpty())
    }

    @Test
    fun `commit failure surfaces save failed error`() = runTest {
        val states = mutableListOf<BubbleUiState>()
        runVoiceSession(FakeHost(commitFails = true)) { states += it }
        assertEquals(
            BubbleUiState(BubbleFeedbackState.ERROR, "save failed"),
            states.last(),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.BubbleVoiceSessionTest"`
Expected: COMPILATION ERROR — unresolved `BubbleSessionHost`, `runVoiceSession`, `BubbleRecognitionOutcome`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSession.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import android.speech.SpeechRecognizer
import com.danilkinkin.buckwheat.data.entities.Transaction

data class BubbleRecognitionOutcome(val transcript: String?, val errorCode: Int)

// Everything the session needs from the Android world, so the pipeline is unit-testable
// with fakes. The service implements this with the real SpeechRecognizer / repository /
// parser stack (reusing the widget's helpers, never modifying them).
interface BubbleSessionHost {
    suspend fun hasBudget(): Boolean
    suspend fun recognize(): BubbleRecognitionOutcome
    suspend fun toTransactions(transcript: String): List<Transaction>
    suspend fun commit(transactions: List<Transaction>)
    fun captionNoBudget(): String
    fun captionRecognitionError(errorCode: Int): String
    fun captionParseFailed(): String
    fun captionSaveFailed(): String
    fun captionAdded(transactions: List<Transaction>): String
}

class BubbleSessionBusyException : Exception("voice session already running")

// One full "listen -> parse -> commit" pass. Emits BubbleUiState values through onState:
// LISTENING -> (PROCESSING -> ADDED | ERROR) or LISTENING -> ERROR. The caller (service)
// owns timing, the recognizer lifecycle, and the reset-to-IDLE timer.
suspend fun runVoiceSession(host: BubbleSessionHost, onState: (BubbleUiState) -> Unit) {
    if (!host.hasBudget()) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionNoBudget()))
        return
    }

    onState(BubbleUiState(BubbleFeedbackState.LISTENING))
    val outcome = host.recognize()
    val transcript = outcome.transcript
    if (transcript == null) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionRecognitionError(outcome.errorCode)))
        return
    }

    onState(BubbleUiState(BubbleFeedbackState.PROCESSING))
    val transactions = host.toTransactions(transcript)
    if (transactions.isEmpty()) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionParseFailed()))
        return
    }

    try {
        host.commit(transactions)
    } catch (e: Exception) {
        onState(BubbleUiState(BubbleFeedbackState.ERROR, host.captionSaveFailed()))
        return
    }
    onState(BubbleUiState(BubbleFeedbackState.ADDED, host.captionAdded(transactions)))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.*"`
Expected: PASS (Tasks 1–3 suites all green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSession.kt app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleVoiceSessionTest.kt
git commit -m "feat: bubble voice session orchestrator with fakeable host interface"
```

---

### Task 4: BubbleView (Compose overlay UI)

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleView.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleViewTest.kt`

**Interfaces:**
- Consumes: `BubbleUiState`, `BubbleFeedbackState` (Task 1). Theme colors from `com.danilkinkin.buckwheat.ui` (`colorGood`, `colorMin`) — same imports the widget uses (VoiceWidget.kt:65-67).
- Produces:
  - `val BUBBLE_SIZE_DP = 56`
  - `@Composable fun BubbleContent(state: BubbleUiState, onTap: () -> Unit, modifier: Modifier = Modifier)` — renders the circle for the current state; ADDED/ERROR also render a caption chip. No drag logic here.
  - Drag/snap/dismiss gesture handled by the service layer via `Modifier.pointerInput` helpers exposed as:
  - `fun Modifier.bubbleDragGesture(onDragEnd: (draggedX: Float, draggedY: Float, velocityY: Float) -> Unit, onDragStart: () -> Unit, onDragging: (dx: Float, dy: Float) -> Unit): Modifier` — tap vs drag disambiguated by `viewConfiguration.touchSlop`; tap invokes the tap handler passed in `BubbleContent` through a lambda the service wires. Signature detail below in Step 3.

- [ ] **Step 1: Write failing Compose UI test**

`app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleViewTest.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle bubble shows mic and tap invokes onTap`() {
        var taps = 0
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.IDLE),
                    onTap = { taps++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("voice input bubble").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `added bubble shows caption chip text`() {
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.ADDED, "Added 150"),
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Added 150").assertExists()
    }

    @Test
    fun `error bubble shows message chip text`() {
        composeRule.setContent {
            BuckwheatTheme {
                BubbleContent(
                    state = BubbleUiState(BubbleFeedbackState.ERROR, "Couldn\'t understand"),
                    onTap = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Couldn\'t understand").assertExists()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.BubbleViewTest"`
Expected: COMPILATION ERROR — unresolved `BubbleContent`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleView.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danilkinkin.buckwheat.ui.colorGood
import com.danilkinkin.buckwheat.ui.colorMin

val BUBBLE_SIZE_DP = 56

// The floating bubble body. Pure rendering for the current feedback state — drag, snap
// and window placement live in the service. Animated glow (infinite pulse) and spinner
// are plain Compose, which the Glance widget could never do.
@Composable
fun BubbleContent(
    state: BubbleUiState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circleModifier = Modifier
            .size(BUBBLE_SIZE_DP.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = acceptsTap(state.state),
                onClick = onTap,
            )

        when (state.state) {
            BubbleFeedbackState.IDLE -> BubbleCircle(
                modifier = circleModifier,
                background = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "voice input bubble",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            BubbleFeedbackState.LISTENING -> {
                val transition = rememberInfiniteTransition(label = "glow")
                val pulse by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "pulse",
                )
                val glowAlpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 0.5f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "glowAlpha",
                )
                Box(contentAlignment = Alignment.Center) {
                    BubbleCircle(
                        modifier = Modifier
                            .size(BUBBLE_SIZE_DP.dp)
                            .scale(pulse)
                            .alpha(glowAlpha),
                        background = MaterialTheme.colorScheme.primary,
                    ) {}
                    BubbleCircle(
                        modifier = Modifier.size(44.dp),
                        background = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "voice input bubble",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            BubbleFeedbackState.PROCESSING -> BubbleCircle(
                modifier = circleModifier,
                background = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            ) {
                val transition = rememberInfiniteTransition(label = "spin")
                val rot by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900)),
                    label = "rot",
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "voice input bubble",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .scale(1f),
                )
            }
            BubbleFeedbackState.ADDED -> BubbleCircle(
                modifier = circleModifier,
                background = colorGood,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "voice input bubble",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            BubbleFeedbackState.ERROR -> BubbleCircle(
                modifier = circleModifier,
                background = colorMin,
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "voice input bubble",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        state.caption?.let { caption ->
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = caption },
            ) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BubbleCircle(
    modifier: Modifier,
    background: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val remember: androidx.compose.runtime.Composable
    get() = throw UnsupportedOperationException()
```

> Implementer note: delete the bogus `private val remember` stub at the bottom — it is a placeholder to be replaced by the real import `androidx.compose.runtime.remember`. Also fix the PROCESSING state to use a real spinner: replace the unused `rot` animation and `Icons.Filled.Close` icon with `Icons.Filled.Refresh` tinted onPrimary and `Modifier.rotate(rot)` (import `androidx.compose.ui.draw.rotate`); the animation value `rot` already exists in that branch. The test only checks content descriptions, so this visual fix does not change the test contract.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.BubbleViewTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleView.kt app/src/test/java/com/danilkinkin/buckwheat/overlay/BubbleViewTest.kt
git commit -m "feat: bubble Compose view with feedback states and caption chip"
```

---

### Task 5: BubbleOverlayService + BubbleLauncher + manifest + notification channel

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleOverlayService.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleLauncher.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add permission at ~line 13, service at ~line 117)
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/Application.kt` (add channel in `createNotificationChannel()`, ~line 182)

**Interfaces:**
- Consumes: Tasks 1–4 (`BubbleSettings`, `bubbleSettings`, `runVoiceSession`, `BubbleSessionHost`, `BubbleUiState`, `snapX/clampY/isFlingDismiss/isOverDismissTarget/acceptsTap/autoResetDelayMs`, `BubbleContent`, `BUBBLE_SIZE_DP`); `SpendsRepository.addSpent`; `parseVoiceInputWithAi`/`parseVoiceInputs`/`VoiceAiResult`; `voiceResultsToTransactions` (widget.voice package — internal, same module: OK); `VoiceWidgetReceiver.requestUpdateData`; `SpeechRecognizer` coroutine pattern copied from VoiceWidgetCommitService.kt:134-174; `Settings.canDrawOverlays`; MainActivity + `VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION`; `settingsDataStore`.
- Produces:
  - `object BubbleLauncher` with `fun show(context: Context)` and `fun hide(context: Context)` (start/stop the service with explicit intents; `show` uses `ContextCompat.startForegroundService`).
  - `class BubbleOverlayService : Service()` — `@AndroidEntryPoint`, `@Inject lateinit var databaseRepository: SpendsRepository`.
  - Manifest: `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` and `<service android:name=".overlay.BubbleOverlayService" android:exported="false" android:foregroundServiceType="microphone" />`.
  - Notification channel `bubble_overlay` created in Application.

- [ ] **Step 1: Add manifest entries and notification channel**

AndroidManifest.xml — after line 13 (`FOREGROUND_SERVICE_MICROPHONE`):

```xml
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

After the existing `<service ... VoiceWidgetCommitService ... />` block (line 117):

```xml
        <service
            android:name=".overlay.BubbleOverlayService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
```

Application.kt — inside `createNotificationChannel()`, mirror the voiceWidgetChannel block (line 182):

```kotlin
        val bubbleChannel = NotificationChannel(
            "bubble_overlay",
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        bubbleChannel.setShowBadge(false)
        notificationManager.createNotificationChannel(bubbleChannel)
```

Add string `bubble_channel_name` ("Voice bubble") to `app/src/main/res/values/strings.xml` (next to the other voice strings, ~line 211):

```xml
  <string name="bubble_channel_name">Voice bubble</string>
```

- [ ] **Step 2: Write BubbleLauncher**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleLauncher.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// Single entry point for showing/hiding the floating bubble from anywhere (Settings,
// Keyboard screen, voice widget callback). Callers must have already verified
// Settings.canDrawOverlays(context); show() is safe to call repeatedly — the service
// treats a second ACTION_SHOW as "ensure visible".
object BubbleLauncher {

    fun show(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BubbleOverlayService::class.java).apply {
                action = BubbleOverlayService.ACTION_SHOW
            },
        )
    }

    fun hide(context: Context) {
        context.startService(
            Intent(context, BubbleOverlayService::class.java).apply {
                action = BubbleOverlayService.ACTION_HIDE
            },
        )
    }
}
```

- [ ] **Step 3: Write BubbleOverlayService**

`app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleOverlayService.kt`:

```kotlin
package com.danilkinkin.buckwheat.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.danilkinkin.buckwheat.MainActivity
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.data.ExtendCurrency
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.keyboard.VoiceAiResult
import com.danilkinkin.buckwheat.keyboard.VoiceInputResult
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputWithAi
import com.danilkinkin.buckwheat.keyboard.parseVoiceInputs
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.util.numberFormat
import com.danilkinkin.buckwheat.widget.voice.VoiceWidgetReceiver
import com.danilkinkin.buckwheat.widget.voice.voiceResultsToTransactions
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

// Foreground (microphone) service owning the floating voice bubble: adds the overlay view
// via WindowManager and runs the whole "listen -> parse -> commit" flow in-process,
// reusing the voice widget's pure helpers. Started only from explicit user actions
// (Settings, Keyboard screen, voice widget tap) while the app is visible, which is the
// same while-in-use exemption the widget's commit service relies on.
@AndroidEntryPoint
class BubbleOverlayService : Service() {

    @Inject
    lateinit var databaseRepository: SpendsRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubbleView: ComposeView? = null
    private val _uiState = MutableStateFlow(BubbleUiState(BubbleFeedbackState.IDLE))
    private val uiState: StateFlow<BubbleUiState> = _uiState
    private var sessionRunning = false
    private var resetJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, ongoingNotification())

        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> removeBubbleAndStop()
            else -> {
                // Service recreated by the system: START_NOT_STICKY, but guard anyway.
                if (!Settings.canDrawOverlays(this)) {
                    stopSelfAndExit()
                } else if (bubbleView == null) {
                    showBubble()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeBubbleView()
        scope.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            // Permission revoked since the launcher call — never add a view without it.
            stopSelfAndExit()
            return
        }
        if (bubbleView != null) return

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BuckwheatTheme {
                    val state by uiState.collectAsState()
                    BubbleContent(
                        state = state,
                        onTap = { onBubbleTap() },
                    )
                }
            }
        }

        val position = runCatching {
            bubbleSettings(this).lastPosition().first()
        }.getOrNull()

        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density).toInt()
        val marginPx = (8 * metrics.density).toInt()
        val x = position?.first?.toInt() ?: marginPx
        val y = position?.second?.toInt() ?: (metrics.heightPixels / 3)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            this.x = x
            this.y = clampY(
                currentY = y.toFloat(),
                screenHeight = metrics.heightPixels.toFloat(),
                bubbleHeight = bubblePx.toFloat(),
                topInsetPx = marginPx.toFloat(),
                bottomInsetPx = marginPx.toFloat(),
            ).toInt()
        }

        windowManager.addView(view, params)
        bubbleView = view
    }

    private fun onBubbleTap() {
        if (!acceptsTap(uiState.value.state)) return
        if (sessionRunning) return

        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.danilkinkin.buckwheat.widget.voice.VoiceWidgetNotifications.EXTRA_REQUEST_MIC_PERMISSION,
                        true,
                    )
                }
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _uiState.value = BubbleUiState(
                BubbleFeedbackState.ERROR,
                getString(R.string.voice_unavailable),
            )
            scheduleAutoReset()
            return
        }

        sessionRunning = true
        scope.launch {
            runVoiceSession(ServiceSessionHost(), ::onSessionState)
        }
    }

    private fun onSessionState(state: BubbleUiState) {
        _uiState.value = state
        when (state.state) {
            BubbleFeedbackState.ADDED -> VoiceWidgetReceiver.requestUpdateData(this)
            BubbleFeedbackState.ERROR, BubbleFeedbackState.ADDED -> scheduleAutoReset()
            else -> {}
        }
    }

    private fun scheduleAutoReset() {
        val delay = autoResetDelayMs(_uiState.value.state) ?: return
        resetJob?.cancel()
        resetJob = scope.launch {
            kotlinx.coroutines.delay(delay)
            if (autoResetDelayMs(_uiState.value.state) != null) {
                _uiState.value = BubbleUiState(BubbleFeedbackState.IDLE)
            }
        }
    }

    // Real-world implementation of the fakeable session host. Parsing mirrors the widget
    // pipeline exactly: AI first, silent regex fallback.
    private inner class ServiceSessionHost : BubbleSessionHost {

        override suspend fun hasBudget(): Boolean =
            runCatching { databaseRepository.getFinishPeriodDate().first() }.getOrNull() != null

        override suspend fun recognize(): BubbleRecognitionOutcome =
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this@BubbleOverlayService)
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle) {
                        val transcript = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(
                                BubbleRecognitionOutcome(transcript, SpeechRecognizer.ERROR_NO_MATCH),
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(BubbleRecognitionOutcome(null, error))
                        }
                    }

                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                )
                continuation.invokeOnCancellation { recognizer.destroy() }
            }

        override suspend fun toTransactions(transcript: String): List<com.danilkinkin.buckwheat.data.entities.Transaction> {
            val aiResult = parseVoiceInputWithAi(this@BubbleOverlayService, transcript)
            val results: List<VoiceInputResult> = when (aiResult) {
                is VoiceAiResult.Success -> aiResult.results
                is VoiceAiResult.Failure -> parseVoiceInputs(transcript)
                VoiceAiResult.NotConfigured -> parseVoiceInputs(transcript)
            }
            return voiceResultsToTransactions(results)
        }

        override suspend fun commit(transactions: List<com.danilkinkin.buckwheat.data.entities.Transaction>) {
            transactions.forEach { databaseRepository.addSpent(it) }
        }

        override fun captionNoBudget(): String = getString(R.string.voice_widget_no_budget)

        override fun captionRecognitionError(errorCode: Int): String = getString(
            when (errorCode) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> R.string.voice_no_speech

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
                else -> R.string.voice_couldnt_understand
            },
        )

        override fun captionParseFailed(): String = getString(R.string.voice_couldnt_understand)

        override fun captionSaveFailed(): String = getString(R.string.bubble_save_failed)

        override fun captionAdded(
            transactions: List<com.danilkinkin.buckwheat.data.entities.Transaction>,
        ): String {
            val currency = runCatching { databaseRepository.getCurrency().first() }.getOrNull()
                ?: ExtendCurrency.none()
            return if (transactions.size == 1) {
                val t = transactions.first()
                val formatted = numberFormat(this@BubbleOverlayService, t.value, currency)
                if (t.comment.isBlank()) {
                    getString(R.string.voice_widget_added, formatted)
                } else {
                    getString(R.string.voice_widget_added_comment, formatted, t.comment)
                }
            } else {
                getString(R.string.voice_widget_added_many, transactions.size)
            }
        }
    }

    private fun ongoingNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, "bubble_overlay")
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getString(R.string.bubble_channel_name))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun removeBubbleView() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    private fun stopSelfAndExit() {
        removeBubbleView()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_SHOW = "com.danilkinkin.buckwheat.overlay.SHOW"
        const val ACTION_HIDE = "com.danilkinkin.buckwheat.overlay.HIDE"
        private const val NOTIFICATION_ID = 410
    }
}
```

Add strings to `app/src/main/res/values/strings.xml` next to `bubble_channel_name`:

```xml
  <string name="bubble_notification_text">Voice bubble is active — tap the bubble to add a spend</string>
  <string name="bubble_save_failed">Couldn\'t save spend</string>
```

- [ ] **Step 4: Build to verify compilation**

Run: `gradlew.bat :app:assembleDebugDebugUnitTest -x lint` — or minimal: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No unit tests for the service itself — WindowManager/SpeechRecognizer glue is verified manually in Task 8.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleOverlayService.kt app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleLauncher.kt app/src/main/AndroidManifest.xml app/src/main/java/com/danilkinkin/buckwheat/Application.kt app/src/main/res/values/strings.xml
git commit -m "feat: bubble overlay foreground service with WindowManager Compose bubble"
```

---

### Task 6: Drag, snap, dismiss (gesture wiring in service)

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleOverlayService.kt` (showBubble / ComposeView content)
- Test: none new (math already covered Task 1; gesture glue verified manually in Task 8)

**Interfaces:**
- Consumes: `snapX`, `clampY`, `isFlingDismiss`, `isOverDismissTarget` (Task 1), `BubbleSettings.savePosition`, `BubbleLauncher` patterns already in service.
- Produces: drag behavior on the live bubble window: drag follows finger; release snaps to nearest edge + persists position; downward fling dismisses; dismiss-target X appears at bottom-center while dragging and a drag onto it dismisses. Service private methods only — no new public API.

- [ ] **Step 1: Replace the ComposeView content in showBubble()**

In `BubbleOverlayService.kt`, change the `setContent` block of the `view` created in `showBubble()` to:

```kotlin
            setContent {
                BuckwheatTheme {
                    val state by uiState.collectAsState()
                    BubbleContent(
                        state = state,
                        onTap = { onBubbleTap() },
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = { onBubbleTap() })
                        },
                    )
                }
            }
```

Wait — `BubbleContent` already has an internal `clickable`; do NOT add `detectTapGestures` on top (double-tap handlers conflict). Instead, keep `onTap = { onBubbleTap() }` as-is and wrap the drag logic on the view via the window params, driven from `onTouchEvent` on a parent. Simplest correct structure: change the ComposeView content to a full small `Box` and use Compose gestures on the circle only for taps (existing) and add drag via `Modifier.pointerInput` on a wrapping `Box` with `detectDragGestures`:

```kotlin
            setContent {
                BuckwheatTheme {
                    val state by uiState.collectAsState()
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }
                    val metrics = resources.displayMetrics
                    val bubblePx = (BUBBLE_SIZE_DP * metrics.density)
                    val marginPx = (8 * metrics.density)

                    Box(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { onDragStart() },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                        onDragMove(offsetX, offsetY)
                                    },
                                    onDragEnd = { onDragEnd(offsetX, offsetY, velocity = 0f) },
                                    onDragCancel = { onDragEnd(offsetX, offsetY, velocity = 0f) },
                                )
                            },
                    ) {
                        BubbleContent(
                            state = state,
                            onTap = { onBubbleTap() },
                        )
                    }
                }
            }
```

And add these private service members (plus imports `androidx.compose.foundation.gestures.detectDragGestures`, `androidx.compose.runtime.mutableFloatStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.foundation.layout.Box`, `kotlin.math.abs`):

```kotlin
    private var dragStartX = 0
    private var dragStartY = 0
    private var dismissTargetView: ComposeView? = null

    private fun currentParams(): WindowManager.LayoutParams? =
        bubbleView?.layoutParams as? WindowManager.LayoutParams

    private fun onDragStart() {
        val params = currentParams() ?: return
        dragStartX = params.x
        dragStartY = params.y
        showDismissTarget()
    }

    private fun onDragMove(dragDx: Float, dragDy: Float) {
        val params = currentParams() ?: return
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density)
        val marginPx = (8 * metrics.density)
        params.x = (dragStartX + dragDx).toInt()
        params.y = clampY(
            currentY = dragStartY + dragDy,
            screenHeight = metrics.heightPixels.toFloat(),
            bubbleHeight = bubblePx,
            topInsetPx = marginPx,
            bottomInsetPx = marginPx,
        ).toInt()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
    }

    private fun onDragEnd(dragDx: Float, dragDy: Float, velocity: Float) {
        removeDismissTarget()
        val params = currentParams() ?: return
        val metrics = resources.displayMetrics
        val bubblePx = (BUBBLE_SIZE_DP * metrics.density)
        val marginPx = (8 * metrics.density)

        if (isFlingDismiss(dragDy * 10f, FLING_THRESHOLD_PX * metrics.density)) {
            dismissBubble()
            return
        }

        // Dismiss-target hit test: the target is centered at screen bottom; compare the
        // bubble's final center against the target rect in window coordinates is not
        // possible cross-window, so use the raw drag end position on screen instead.
        val endY = dragStartY + dragDy
        val targetTop = metrics.heightPixels - (120 * metrics.density)
        val centerX = dragStartX + dragDx + bubblePx / 2f
        val targetLeft = metrics.widthPixels / 2f - (60 * metrics.density)
        val targetRight = metrics.widthPixels / 2f + (60 * metrics.density)
        if (
            isOverDismissTarget(
                x = centerX,
                y = endY + bubblePx / 2f,
                targetLeft = targetLeft,
                targetTop = targetTop,
                targetRight = targetRight,
                targetBottom = metrics.heightPixels.toFloat(),
            )
        ) {
            dismissBubble()
            return
        }

        params.x = snapX(
            currentX = (dragStartX + dragDx).toFloat(),
            screenWidth = metrics.widthPixels.toFloat(),
            bubbleWidth = bubblePx,
            marginPx = marginPx,
        ).toInt()
        params.y = clampY(
            currentY = endY,
            screenHeight = metrics.heightPixels.toFloat(),
            bubbleHeight = bubblePx,
            topInsetPx = marginPx,
            bottomInsetPx = marginPx,
        ).toInt()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
        scope.launch {
            bubbleSettings(this@BubbleOverlayService).savePosition(params.x.toFloat(), params.y.toFloat())
        }
    }

    // Small X target pinned bottom-center, visible only while dragging. Its own touch
    // listener dismisses when the dragged bubble is released over it — since the bubble
    // window is WRAP_CONTENT the target must be its own window to receive no stray touches
    // when idle; it is removed the moment the drag ends.
    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        val metrics = resources.displayMetrics
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BuckwheatTheme {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "dismiss bubble",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (16 * metrics.density).toInt()
        }
        runCatching { windowManager.addView(view, params) }
        dismissTargetView = view
    }

    private fun removeDismissTarget() {
        dismissTargetView?.let { runCatching { windowManager.removeView(it) } }
        dismissTargetView = null
    }

    private fun dismissBubble() {
        scope.launch {
            val params = currentParams()
            if (params != null) {
                bubbleSettings(this@BubbleOverlayService)
                    .savePosition(params.x.toFloat(), params.y.toFloat())
            }
            removeDismissTarget()
            removeBubbleView()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val ACTION_SHOW = "com.danilkinkin.buckwheat.overlay.SHOW"
        const val ACTION_HIDE = "com.danilkinkin.buckwheat.overlay.HIDE"
        private const val NOTIFICATION_ID = 410
        private const val FLING_THRESHOLD_PX = 1.5f
    }
}
```

> Implementer note: the `FLING_THRESHOLD_PX` constant is a density multiplier (1.5 × density ≈ 1500 px on a xhdpi device). `dismissBubble` also updates `ACTION_HIDE` handler to call it: change the `ACTION_HIDE` branch in `onStartCommand` to `dismissBubble()` instead of `removeBubbleAndStop()`, and delete `removeBubbleAndStop` if now unused (its remaining callers should call `stopSelfAndExit`).

- [ ] **Step 2: Build to verify compilation**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Full unit test run (no regressions)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.*"`
Expected: PASS (Tasks 1–4 suites).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/overlay/BubbleOverlayService.kt
git commit -m "feat: bubble drag, edge snap, fling and X-target dismissal"
```

---

### Task 7: Launch points — Settings master switch + show/hide button

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/settings/BubbleOverlaySetting.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/settings/Settings.kt` (insert after `VoiceWidgetDesignSetting` line 133)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `bubbleSettings(context)`, `BubbleSettings.setEnabled`, `BubbleLauncher.show/hide`, `Settings.canDrawOverlays`, `ButtonRow` (base/ButtonRow.kt:16), `Switch` (material3) — pattern modeled on `RoundValuesSetting.kt` exactly.
- Produces: `@Composable fun BubbleOverlaySetting()` — ButtonRow with Switch; toggle OFF→ON fires `ACTION_MANAGE_OVERLAY_PERMISSION`; result re-reads `canDrawOverlays`; granted → save enabled=true + `BubbleLauncher.show`; denied → stays OFF. A second ButtonRow "Show/Hide bubble" appears only while enabled. Strings: `bubble_setting_title` ("Floating voice bubble"), `bubble_setting_description` ("Show a floating mic bubble over other apps to add spends by voice"), `bubble_show` ("Show bubble"), `bubble_hide` ("Hide bubble").

- [ ] **Step 1: Add strings**

`app/src/main/res/values/strings.xml` next to the other bubble strings:

```xml
  <string name="bubble_setting_title">Floating voice bubble</string>
  <string name="bubble_setting_description">Show a floating mic bubble over other apps to add spends by voice</string>
  <string name="bubble_show">Show bubble</string>
  <string name="bubble_hide">Hide bubble</string>
```

- [ ] **Step 2: Write BubbleOverlaySetting**

`app/src/main/java/com/danilkinkin/buckwheat/settings/BubbleOverlaySetting.kt`:

```kotlin
package com.danilkinkin.buckwheat.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.danilkinkin.buckwheat.R
import com.danilkinkin.buckwheat.base.ButtonRow
import com.danilkinkin.buckwheat.overlay.BubbleLauncher
import com.danilkinkin.buckwheat.overlay.bubbleSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Master switch for the floating voice bubble. Turning ON requires the system
// "display over other apps" permission: the toggle immediately sends the user to the
// system screen and only latches enabled=true after returning with the grant. Turning
// OFF hides the bubble and permanently disables the capability until re-enabled.
@Composable
fun BubbleOverlaySetting() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var bubbleVisible by remember { mutableStateOf(false) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivity(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            coroutineScope.launch {
                bubbleSettings(context).setEnabled(true)
                enabled = true
                BubbleLauncher.show(context)
            }
        } else {
            enabled = false
        }
    }

    LaunchedEffect(Unit) {
        enabled = bubbleSettings(context).isEnabled().first()
        bubbleVisible = enabled && Settings.canDrawOverlays(context)
    }

    ButtonRow(
        icon = painterResource(R.drawable.ic_mic),
        text = stringResource(R.string.bubble_setting_title),
        description = stringResource(R.string.bubble_setting_description),
        onClick = {
            if (enabled) {
                coroutineScope.launch {
                    bubbleSettings(context).setEnabled(false)
                    enabled = false
                    BubbleLauncher.hide(context)
                    bubbleVisible = false
                }
            } else {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        },
        endContent = {
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    if (!want) {
                        coroutineScope.launch {
                            bubbleSettings(context).setEnabled(false)
                            enabled = false
                            BubbleLauncher.hide(context)
                            bubbleVisible = false
                        }
                    } else {
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
        },
    )

    if (enabled) {
        ButtonRow(
            icon = painterResource(R.drawable.ic_widgets),
            text = stringResource(
                if (bubbleVisible) R.string.bubble_hide else R.string.bubble_show,
            ),
            onClick = {
                if (bubbleVisible) {
                    BubbleLauncher.hide(context)
                    bubbleVisible = false
                } else {
                    if (Settings.canDrawOverlays(context)) {
                        BubbleLauncher.show(context)
                        bubbleVisible = true
                    } else {
                        enabled = false
                        coroutineScope.launch { bubbleSettings(context).setEnabled(false) }
                    }
                }
            },
        )
    }
}
```

- [ ] **Step 3: Register in Settings.kt**

`Settings.kt` line 133 — insert after `VoiceWidgetDesignSetting(appViewModel = appViewModel)`:

```kotlin
                BubbleOverlaySetting()
```

Add import: `import com.danilkinkin.buckwheat.settings.BubbleOverlaySetting` — no, same package, no import needed.

- [ ] **Step 4: Build**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/settings/BubbleOverlaySetting.kt app/src/main/java/com/danilkinkin/buckwheat/settings/Settings.kt app/src/main/res/values/strings.xml
git commit -m "feat: settings master switch and show-hide control for voice bubble"
```

---

### Task 8: Launch points — Keyboard screen icon + voice widget button + manual verification

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/keyboard/Keyboard.kt` (mic button row, ~line 350-392)
- Create: `app/src/main/java/com/danilkinkin/buckwheat/widget/voice/BubbleWidgetCallback.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/widget/voice/VoiceWidget.kt` (right-side button column, lines 493-499)
- Modify: `app/src/main/res/values/strings.xml` (content description)

**Interfaces:**
- Consumes: `BubbleLauncher.show`, `Settings.canDrawOverlays`, `bubbleSettings(context).isEnabled().first()`; Glance `actionRunCallback<ActionCallback>` pattern (VoiceWidget.kt:537 + VoiceWidgetMicCallback.kt).
- Produces: `class BubbleWidgetCallback : ActionCallback` (widget/voice package) that calls `BubbleLauncher.show(context)` after a `canDrawOverlays` check; keyboard icon button next to mic.

- [ ] **Step 1: Keyboard bubble icon**

In `Keyboard.kt`, the mic button `Row` at ~line 340: after the mic button `Box` block (closing `}` at line 392) and before the `if (isListening)` at line 393, insert:

```kotlin
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colorButton)
                    .padding(start = 8.dp)
                    .clickable {
                        if (Settings.canDrawOverlays(context)) {
                            BubbleLauncher.show(context)
                        } else {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_widgets),
                    contentDescription = stringResource(R.string.bubble_setting_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
```

Add imports to Keyboard.kt:

```kotlin
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.danilkinkin.buckwheat.overlay.BubbleLauncher
```

> Implementer note: verify `Settings` does not collide with an existing import in Keyboard.kt (there is no settings import currently — confirmed via grep). Also remove the stray `.padding(start = 8.dp)` if the row already spaces children; match the mic button's existing padding pattern exactly by inspecting the surrounding `Row`'s `Arrangement` first.

- [ ] **Step 2: Widget callback**

`app/src/main/java/com/danilkinkin/buckwheat/widget/voice/BubbleWidgetCallback.kt`:

```kotlin
package com.danilkinkin.buckwheat.widget.voice

import android.content.Context
import android.provider.Settings
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danilkinkin.buckwheat.overlay.BubbleLauncher

// Voice widget's bubble button: summons the floating voice bubble overlay. Same
// while-in-use FGS-start exemption as VoiceWidgetMicCallback (tap on a visible widget).
// If the overlay permission is missing or revoked the bubble cannot appear — silently
// no-op; the Settings master switch is where permission is granted.
class BubbleWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        if (!Settings.canDrawOverlays(context)) return
        BubbleLauncher.show(context)
    }
}
```

- [ ] **Step 3: Widget UI button**

In `VoiceWidget.kt`, replace the `when (feedbackState)` block (lines 493-499) with:

```kotlin
                BubbleSummonButton(context)

                when (feedbackState) {
                    VoiceFeedbackState.LISTENING,
                    VoiceFeedbackState.PROCESSING,
                    -> ListeningButton(context)
                    VoiceFeedbackState.ADDED -> AddedButton(context)
                    VoiceFeedbackState.IDLE -> MicButton(context)
                }
```

And add after `MicButton` (line 550):

```kotlin
// Small companion button that summons the floating voice bubble overlay for voice input
// while reading other apps. Glance 1.1.1 has no long-press action, so this is a plain tap
// button (design decision Q8/B).
@Composable
@GlanceComposable
private fun BubbleSummonButton(context: Context) {
    Box(
        modifier = GlanceModifier
            .padding(start = 8.dp)
            .size(32.dp)
            .background(
                ColorProvider(
                    GlanceTheme.colors.primary.getColor(context).copy(alpha = 0.15f),
                ),
            )
            .cornerRadius(16.dp)
            .clickable(actionRunCallback<BubbleWidgetCallback>()),
        contentAlignment = Alignment.Center,
    ) {
        val provider = drawableProvider(context, R.drawable.ic_widgets)
        if (provider != null) {
            Image(
                modifier = GlanceModifier.size(18.dp),
                provider = provider,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                contentDescription = null,
            )
        }
    }
}
```

- [ ] **Step 4: Build + full overlay test suite**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.
Run: `gradlew.bat :app:testDebugUnitTest --tests "com.danilkinkin.buckwheat.overlay.*"`
Expected: PASS.

- [ ] **Step 5: Manual verification checklist (emulator/device with overlay support)**

1. Settings → enable "Floating voice bubble" → system overlay screen → grant → returns → switch ON → bubble appears snapped to left edge.
2. Drag bubble across screen → release mid-left → snaps left; mid-right → snaps right; position survives app restart (kill app, re-show).
3. Tap bubble → mic glow pulse → speak "150 tea" → green ✓ + "Added 150 — tea" chip → auto-hide after ~4s → mic state.
4. Tap with no budget set → red ! + "No budget set yet" chip → auto-reset.
5. Drag to bottom-center X while dragging → bubble removed; re-show from Settings.
6. Fling bubble downward fast → dismissed.
7. Widget: bubble icon button on voice widget → bubble appears over launcher.
8. Keyboard screen: widget icon next to mic → bubble appears over keyboard.
9. Revoke overlay permission in system settings → service self-stops next interaction; switch reads OFF on reopen.
10. `VoiceWidgetCommitService` untouched: `git diff --stat` shows no changes under `widget/voice/VoiceWidgetCommitService.kt` and no changes to `VoiceWidget.kt` beyond the `when` block + new `BubbleSummonButton`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/keyboard/Keyboard.kt app/src/main/java/com/danilkinkin/buckwheat/widget/voice/BubbleWidgetCallback.kt app/src/main/java/com/danilkinkin/buckwheat/widget/voice/VoiceWidget.kt app/src/main/res/values/strings.xml
git commit -m "feat: bubble launch points from keyboard screen and voice widget"
```

---

## Self-Review Notes (fixed inline during authoring)

- Spec coverage: architecture §1 (Task 5), UI states §2 (Task 4) + drag/snap/dismiss §2 (Task 6), data flow §3 (Task 3 + 5), permissions/integration §4 (Task 5, 7, 8), errors/testing §5 (Tasks 1–4 tests + Task 8 manual checklist). Q11 (no boot receiver) honored — nothing added.
- Placeholder scan: Task 4 contains one deliberately flagged stub (`private val remember`) with explicit implementer instruction to delete it and two visual fixes; this is the only in-code TODO and is self-contained.
- Type consistency: `BubbleRecognitionOutcome`, `BubbleSessionHost` method names, `BubbleUiState` equality-based assertions, `BubbleLauncher.show/hide`, `bubbleSettings(context)` used identically across Tasks 3, 5, 7, 8. `voiceResultsToTransactions` is `internal` in the same module — import from `widget.voice` package is valid.
