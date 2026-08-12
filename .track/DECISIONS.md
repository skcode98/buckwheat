# Decisions Log

> Every decision taken during feature work, with the reasoning behind it. Append newest at the top.
> Format: **Date — Decision** — why.

---

## 2026-08-12 — Decision: AI report belongs in Settings (not Analytics); interleaved + category-caps UIs simplified

**Decision**: User feedback ("AI report it's just normal text and placement is also wrong, add it in setting page not in analytics; interleaved budget looks messy in analytics; category caps settings UI is a mess"). Moved the AI report out of the analytics column into a dedicated Settings sheet (`AI_INSIGHT_SHEET` under a new "AI Insight" row), rendered the report's `• ` bullets as real bullet rows, replaced the interleaved card's wordy velocity sentence with one compact status caption, and swapped the per-row frequency dropdown in the caps sheet for four FilterChips.

**Why**: Analytics is for period stats — an on-demand AI card buried there was hard to find and visually competed with the numbers; Settings is where the rest of the AI controls already live ("Voice AI" row directly above). Bullet rows read better than a plain paragraph. Chips are one-tap and match the app's chip language; the dropdown OutlinedTextField was heavy for a 4-value choice. Shorter status text removes wrapping on small screens. **Outcome**: golden pipeline green, 286 tests, 0 failures. Kept-in-string-but-unused: `category_caps_frequency`, `category_caps_anchor_hint`, `category_caps_templates_title` (UI labels no longer rendered).

## 2026-08-12 — Decision: Ship a "Repeat last spend" editor quick action as the next feature

**Decision**: From the 20-candidate feature list in `.track/FEATURES_AND_IMPROVEMENTS.md`, implement **Repeat-last-spend quick action** (`editor/RepeatLastSpend.kt` pure helper + `EditorViewModel.startRepeatSpend()` + an `AssistChip` in the Editor).

**Why**:
- Tier-1 rating: Small complexity, High daily value. The app's fastest daily loop is "add a spend" — a repeat chip cuts the most common re-entry (coffee/transport/everyday small spends) to one tap.
- Pure logic (`lastSpendToRepeat`) is trivially unit-testable without Android dependencies, matching the repo's Robolectric/JVM test style.
- Self-contained: touches Editor + EditorViewModel + one string; no DB migration, no DataStore schema change, no notification wiring. Lowest regression risk of the high-value candidates.
- Fit with existing architecture: the editor already routes commits through `SpendsViewModel.addSpent`; prefilling `currentSpent/currentComment/currentCategory/rawSpentValue` and setting stage `EDIT_SPENT` reuses the whole commit path unchanged.

**Rejected alternatives (with reasons)**:
- Tap-to-edit + long-press in History (Tier 1, High) — higher value but touches the `SwipeActions` gesture stack (risk of gesture conflicts) and is UI-only (hard to unit test).
- Category chips on history rows — lower daily frequency; display-only change.
- App lock (PIN/biometric) — Large complexity, security surface, needs `BiometricPrompt` + new settings; better as its own focused session.
- Per-category widget / savings widget — medium complexity; widget E2E can't be verified without an emulator.
- Multi-period trend — medium; needs new Room aggregate + new card; more surface.
- `whatBudgetForDay` widget/repository unification — a correctness win but silently touches widget rendering; no emulator to verify.

**Outcome**: Implemented — pure `editor/RepeatLastSpend.kt` (`lastSpendToRepeat` filters SPENT, most recent by date then uid), `EditorViewModel.startRepeatSpend`, an `AssistChip` in `Editor.kt` fed from `periodSpends`, string `repeat_last_spend`. Confirming commits a NEW row (`editedTransaction = null`, `mode = ADD`, `stage = EDIT_SPENT`) — the old transaction is never overwritten. Golden pipeline green: **286 tests, 0 failures** (5 new `RepeatLastSpendTest`). NOTE: still uncommitted pending user sign-off (user's "not good" feedback addressed the AI report / interleaved / caps UI, not this feature — see the UI-cleanup decision above).

---

## 2026-08-12 — Decision: Two new tracking docs, `DECISIONS.md` + `FEATURES_AND_IMPROVEMENTS.md`

**Decision**: Add `.track/DECISIONS.md` (this file) recording decisions + rationale, and `.track/FEATURES_AND_IMPROVEMENTS.md` listing candidate work with complexity/value notes.

**Why**: The user asked for a durable record of (a) decisions and why they were taken, and (b) any potential feature/enhancement/improvement across any feature, functionality, or file. Keeping these as living `.track/` docs (same convention as `CHANGELOG`/`MEMORY`/`CACHE`) means a compacted future session can recover the full context. The candidates were verified as genuinely missing by a codebase-wide exploration agent (`2026-08-12`).

---

## 2026-08-11 — Decision: Committed the big batch as one commit per item, then pushed (A `b23b12d`, B `c39e21b`, C `124491e`, D `d61edc4`, E `84f5aaf`)

**Why**: The plan's definition of done was "each item golden-pipeline green; committed + pushed". Splitting `SpendsRepository.kt` hunks (Task B archive-category vs Task C interleaved allowance) was done via `git add -p` interactive staging so each commit stays logically atomic and bisectable.

---

## 2026-08-11 — Decision: Phase 5 allowance formula = per-day `dailyPace`, NOT the draft's literal `monthlyEquivalent`

**Why**: `monthlyEquivalent` would reserve ~30× the intended amount (the plan's own example: FOOD monthly @5000 ≈ ₹166/day requires `amount/(freqMonths×30)`). `applyCategoryAllowance(raw, allowance) = max(raw − allowance, raw×0.80, 0)` normalized to scale 2. Applied only at `whatBudgetForDay`/`nextDayBudget` returns so `updateDailyBudget`/`setDailyBudget` and no-schedule users are untouched.

---

## 2026-08-11 — Decision: Phase 6 miner seeds schedules only for categories with NO existing schedule

**Why**: User-configured schedules must always win over machine-mined suggestions. `SettingsRepository.applyScheduleSuggestions` skips any category that already has a schedule, keeping user choices authoritative. Real-data calibration remains blocked on the user's 6-month export.

---

## 2026-08-11 — Decision: Archived categories live in the `archived_transactions` table (DB v14), not a new join table

**Why**: Mirrors the existing `transactions.category` column; single ALTER + migration is the smallest, safest schema change and keeps backup codec + `toTransaction()` trivial. Restore stays backward compatible (v1 exports round-trip via `optString` null-default).

---

## 2026-08-11 — Decision: `OnTrackAlertScheduler` moved from `setRepeating` to one-shot `setWindow` + receiver re-arm

**Why**: `setRepeating` is inexact on modern Android (~18h drift observed on the widget-refresh fix). One-shot `setWindow` bounds the delay to ≤10 min; the receiver re-arms next day only while the toggle is enabled. Same fix already applied to daily reminder, widget refresh, digest, and recurring-due alerts.

---

## 2026-08-10 — Decision: De-linked the app (removed About links, BugReporter, crash-email)

**Why**: Security audit rules (`.track/SECURITY.md`). Zero external contacts/analytics/ads SDKs; only outbound endpoint is the user-chosen Voice AI provider. Crash logs are local-only (Downloads). API key excluded from all backups + cloud transfer.

---

## 2026-08-09 — Decision: Voice AI default model → `openai/gpt-oss-20b:free`, defaults centralized

**Why**: Live OpenRouter listing showed the old `nvidia/nemotron-3-ultra-550b-a55b:free` is rate-limited (HTTP 429) → surfaced as "wrong parse / no AI". Centralizing defaults + `normalizeVoiceAiModel` auto-upgrades already-saved settings on-device without a sheet visit.

---

## 2026-08-08 — Decision: Midnight widget refresh uses `setWindow` one-shot, not `setRepeating`

**Why**: `setRepeating` was observed (dumpsys) to drift ~18h; `setWindow(RTC_WAKEUP, trigger, 10min)` bounds it without needing `SCHEDULE_EXACT_ALARM`. Receiver re-arms after firing.

---

## 2026-08-08 — Decision: Analytics "vs previous" compares day-granularity via `effectiveFinishDate = actualFinishDate ?: finishDate`

**Why**: `finishDate` is stored end-of-day; a previous period finished early (or restarted mid-period) never qualified → card vanished. Day-granular comparison fixes it without touching stored data.

---

## 2026-08-07 — Decision: Restore is transactional (`BackupRepository` gets `DatabaseModule` param); API key excluded from exports

**Why**: Review-wave fixes — restore must be all-or-nothing to avoid a half-restored DB; the Voice AI key is the only credential and must never leave the device.

---

## 2026-08-07 — Decision: `overspendNotifiedStoreKey` is a "currently over" mirror kept in sync inside every `edit {}` that mutates today's counter/budget

**Why**: The instant overspend notification must fire exactly once when crossing, and re-fire when crossing again after recovering. Keeping the mirror inside the same `edit {}` block prevents a stray reader from firing duplicates.

---

## 2026-08-06 — Decision: Currency = curated 12-code list, INR default

**Why**: User request ("only a few important currencies, Rupee default"). Fresh installs get ₹; existing picks outside the shortlist are preserved via `remember(defaultCurrency)` prepend. Explicit "No currency" still stores `""` → NONE.

---

## 2026-08-06 — Decision: Categories management as a Room entity (`SavedCategory`, migration 11→12) rather than comment-derived only

**Why**: User asked for management "like tags". Persistent entity gives stable ids for edit/delete and later analytics; mirrors the `saved_tags` migration pattern.

---

## 2026-08-06 — Decision: Period picker limited to current + next month

**Why**: The onboarding calendar showed ~2 years (default listMonths bounds). Restricting to `now.withDayOfMonth(1)`..`last day of next month` makes it usable. Overrides the earlier "start up to one month back" decision.

---

## 2026-08-06 — Decision: Full JSON backup/restore covers every Room entity + both DataStores, ids preserved for FK integrity

**Why**: FK-safe reinsert order (periods → archived → transactions → tags → categories → recurring → goals) and id preservation keep `archived_transactions.period_id` links alive. Restore replaces all current data (with confirmation) — partial failure recoverable by re-restore.

---

## 2026-08-06 — Decision: AI spend categories persisted to the DB via `transactions.category`, not re-classified every render

**Why**: Classification is expensive (network) and non-deterministic per run; persisting makes analytics stable and offline-correct. Fallback: offline keyword classifier when no category persisted.

---

## 2026-08-05 — Decision: Voice widget renders chart/drawings into `android.graphics.Bitmap` at composition time

**Why**: Glance 1.1.1 has no canvas/path composables (`androidx.glance.canvas` absent) and `ContentScale` is only `{ Fit, Crop, FillBounds }`. Bitmap drawing with `ContentScale.FillBounds` is the only way to draw custom charts/rings/bars.

---

## 2026-08-04 — Decision: `howMuchBudgetRest()` returns a cached `MediatorLiveData`, never constructs a LiveData per call

**Why**: The previous version created a new LiveData (+ coroutine) on every call → infinite recomposition loop / freezes on the wallet screen. Rule: hoist LiveData/Flow to the ViewModel and cache.

---

## 2026-08-03 — Decision: No `runBlocking` in composables/ViewModels; no `!!`, no `.first()` on empty lists, no split `edit {}` blocks

**Why**: These are the repo's hard rules (`AGENTS.md`/`SECURITY.md`) — they prevent freezes, crashes, and lost DataStore writes. Enforced in code review.
