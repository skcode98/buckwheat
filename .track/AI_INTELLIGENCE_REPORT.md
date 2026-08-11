# AI Intelligence Report — plan (v1)

## Goal
Give the user a **whole-budget-period AI analysis** inside Analytics: category breakdown summary, biggest expense, spending pace vs the period, overspend days, and a comparison with the previous period — generated through the existing OpenRouter voice-AI settings.

## Scope
- **Single card** (`AiInsightCard`) at the very top of the analytics column (above `WholeBudgetCard`), always visible.
- **No new settings**: reuses the existing `voiceAi*` DataStore keys (API key, provider URL, model) plus the `aiIntelligenceEnabled` master toggle.
- **No Room migration / no schema change.**
- **No `runBlocking`**, no Android imports in the pure prompt/parser logic (unit-testable like the interleaved engine).

## Files

| File | Purpose |
|------|---------|
| `ai/AiInsight.kt` (new) | Pure summary model + prompt builders + `parseAiInsightReport()` + `overspendDayCount()`; `generateAiInsight()` HTTP call |
| `ai/AiInsightViewModel.kt` (new) | `@HiltViewModel`; `state: LiveData<AiInsightUiState>`; `generate()`; `buildSummary()` from `SpendsRepository` |
| `analytics/AiInsightCard.kt` (new) | State-driven card UI (Idle / Loading / Report / Error / NotConfigured) |
| `analytics/Analytics.kt` | Wire ViewModel + card at top of column; settings deep-link via `PathState(VOICE_AI_SETTINGS_SHEET)` |
| `values/strings.xml` | 9 new EN strings (`ai_insight_*`) |
| `test/ai/AiInsightTest.kt` (new) | Pure tests: parser cleanup, prompt content, overspend-day counting |

## Design decisions

### Summary content (`SpendInsightSummary`)
- `currencyCode`, `budget`, `spent`, `startDate/endDate/today` (LocalDate), `transactionCount`
- `categories: List<CategorySpendInsight>` — top categories from `categoryTotals()` with percent (`amount*100/spent`, `HALF_EVEN`); category names use the stored `CategoryKey` names
- `biggestSpend` + `biggestSpendComment` (from `maxByOrNull { value }`)
- `overspendDays` — distinct days within `[start, end]` (capped at today) whose total SPENT exceeded the daily budget; disabled when daily budget ≤ 0
- `previousPeriodTotal` — via `findPreviousPeriod` (existing helper)

### Prompt (plain text, no HTML/JSON, matches AI conventions)
- System: role + rules (currency-aware amounts, no markdown, no bullet-only empty lines, focus on period, compare vs previous, honest "no data" answers, never invent transactions).
- User: budget, spent, remaining (clamped ≥ 0), elapsed days, average/day, transactions, overspend days, previous period total, biggest expense, category breakdown.

### Report cleanup (`parseAiInsightReport`)
- Strip markdown code fences (` ``` `), an echoed `Report:` label, collapse 3+ newlines → 2.

### Networking (`generateAiInsight`)
- Mirrors `VoiceAi.kt` conventions: 10s connect / 20s read timeouts, always `disconnect()` in `finally`, `extractModelContent()` for content, failure body truncated to 200 chars.
- Returns `AiInsightResult.Success(report)` / `.Failure(message)` / `.NotConfigured` (missing API key, or `aiIntelligenceEnabled` off).
- Reads `context.settingsDataStore.data.first()` — never `runBlocking`.

### UI states (`AiInsightUiState`)
`Idle` → CTA "Generate report" always visible; `Loading` → spinner + "Analyzing your spending…"; `Report` → text + "Regenerate"; `Error` → short message + "Try again"; `NotConfigured` → "Set up AI" deep-link to the Voice AI settings sheet. Generate button state is `rememberSaveable`.

## Out of scope
- Custom monthly-report share (rejected earlier by user; the "report" lives inside the app).
- Editing AI settings from the card — deep-link only.
- Voice AI settings sheet redesign.

## Verification
- Golden pipeline: `:app:spotlessApply :app:testDebugUnitTest :app:assembleDebug`.
- New tests cover prompt content, parser cleanup, overspend counting, HTTP failure with a garbage model response.
