# TrackInvest — Android Migration Archive

> **Status: ARCHIVED 2026-08-09.** The TrackInvest web-app → Android migration was closed and the work branch archived. This document is the complete record: source overview, full migration plan with progress, every commit, the built module's architecture, data model, design decisions, deviations, gotchas, test/build status, remaining work, and how to resume. Read this file before touching any TrackInvest code again.

---

## 1. Executive Summary

- **Goal**: Rebuild the TrackInvest web PWA (a wealth manager / portfolio tracker + planner) as an Android app inside the **Buckwheat** repository, in Buckwheat's style (Kotlin 2.2.0, Jetpack Compose + Material3, Room, Hilt, Preferences DataStore), as a **separate Gradle module** `trackinvest` so both apps coexist.
- **Result**: A working `trackinvest` module shipped with 3 tabs (Dashboard / Ledger / Portfolio), full Room schema v1, Hilt DI, DataStore settings, CSV backup/restore, ledger CRUD, recurring SIPs + templates, portfolio math + charts, and tax/80C. **47 unit tests green**, `assembleDebug` BUILD SUCCESSFUL.
- **Decision to archive**: The migration was paused by the owner to return focus to Buckwheat's own improvement/enhancement work. The branch is preserved (tag `archive/trackinvest-migration`) and fully documented here so it can be resumed or mined for patterns later. Nothing is lost.

---

## 2. Repository & Branch Context (how to find the work)

| Item | Value |
|---|---|
| Repo | fork of `danilkinkin/buckwheat`; origin `https://github.com/skcode98/buckwheat` |
| Work branch | `feature/trackinvest-migration` (branch created from `master` @ `bc5e922`) |
| Archive tag | `archive/trackinvest-migration` (points at the final branch HEAD `bbe77bc`) |
| Remote branch | **deleted on archive** — the tag preserves all commits |
| Pre-migration backup | `backup/pre-trackinvest-2026-08-09` = `master` @ `bc5e922` (frozen snapshot, do NOT delete) |
| Resume path | `git checkout archive/trackinvest-migration -b feature/trackinvest-resume` |
| Upstream | `https://github.com/danilkinkin/buckwheat.git` |

The migration branch contains **16 commits** on top of `master` (`bc5e922`). `master` has since been returned to the pre-migration Buckwheat state (`b58f48e` docs commit on top of `bc5e922`) and carries this archive file so the record lives in the mainline.

---

## 3. Source Web App Overview (the thing being migrated)

Source: `D:\Just-try\TrackInvest\TrackInvest\` (v5.44). **Read-only reference** — never edit the web source; it is the specification.

| Aspect | Value |
|---|---|
| Name / version | TrackInvest wealth manager, `v5.44` |
| Stack | Zero npm/build — vanilla HTML + CSS + JS, no framework, no bundler, no tests |
| Storage | Browser `localStorage` only (single-writer key `appHubInvestDb`; accounts in `appHubInvestDb_ao`) |
| PWA | Offline-first service worker, installable, serverless notifications |

### Web source files (reference line anchors)
| File | Lines | Role |
|---|---|---|
| `index.html` | 1448 | Dashboard / Portfolio / Ledger SPA (loads `app_part1/2/3.js`) |
| `monthly_plan.html` | 2340 | Zero-based monthly planner |
| `spend_tracker.html` | 1351 | Expense tracker |
| `account_overview.html` | 878 | Banks / cards / PF + OCR |
| `money_flow.html` | 412 | Lent / borrowed / locked / uncounted |
| `app_part1.js` | 3798 | Core engine (storage, theme, sheets, charts, ledger, recurring, tax/FIRE, MF/stock search, historical SIPs) |
| `app_part2.js` | 3381 | Investment CRUD, goals, FIRE, settings, PIN/AES, backup/restore, AI Hub |
| `app_part3.js` | 1700 | Dashboard cards, portfolio calc, `renderAll`, notifications, health/advisor, PDF report |
| `shared_ai.js` | 348 | AI provider router + shared utils |
| `style.css` | 3210 | Shared MD3 theme (`--md-*` tokens) |
| `TRACK.md` | 186 | Web audit log |

### Key web anchors used during migration
- `app_part1.js:2305` `calculateStrictValuation`
- `app_part1.js:1824` `isCurrentFY`
- `app_part1.js:2232` `calculateStrictTax`
- `app_part1.js:2460` `renderNWChart`
- `app_part1.js:2632` `renderHeroProjectionChart`
- `app_part1.js:3525` historical SIP backfill
- `app_part2.js:251` `saveInvestment` entry shape
- `app_part3.js:39-60` recurring SIP run semantics
- `app_part3.js:173` `updatePortfolioCalculations`
- `app_part3.js:397-416` allocation bar/legend + portfolio grid

---

## 4. Full Migration Plan (with status)

> The master plan lived in `.track/trackinvest.md`. Status is **as of archiving**.

### Phase 0 — Scaffold & foundation ✅ DONE
- [x] 0.1 Add `trackinvest` Gradle module, app identity, theme, bottom nav (`3d32732`)
- [x] 0.2 Room schema v1: 9 entities + DAOs, Hilt, DataStore (`61b5b77`)
- [x] 0.3 CSV backup/restore (`69ac5b7`)
- [ ] 0.4 JSON backup/restore mirroring web `exportData`/`restoreData` (incl. `appHubInvestDb_ao`) — **NOT DONE**

### Phase 1 — Core ledger ✅ (partial)
- [x] 1.1 Investment CRUD — ledger list + add/edit bottom sheet (`15ee70b`)
- [x] 1.4 Templates + recurring SIPs engine (`09ba576`)
- [ ] 1.2 Ledger screen: search/filter/sort/date-range, insights bar, swipe-delete + undo, batch delete — **NOT DONE** (CSV export done in 0.3)
- [ ] 1.3 Add/Edit per-type fields (FD renew auto-close, smart preview/reverse unit calc, live MF/stock search) — **NOT DONE**

### Phase 2 — Dashboard & portfolio math ✅ (partial)
- [x] 2.1 Portfolio calculations (type totals, net worth, monthly invested, P&L, strict valuation, 80C tax) — (`cf59c17`)
- [x] 2.2 Dashboard summary cards + net-worth/projection charts, progress ring, 12-month trend, allocation donut, maturity list — (`cf59c17`); sankey/heatmap/mini-cards/advisor/AI/backtester **deferred**
- [x] 2.3 Portfolio tab: donut + allocation bar/legend, asset grid, maturity list — (`d47c447`); PDF wealth report, goals + AI sync deferred
- [ ] 2.4 Goals + FIRE sheet + portfolio health score — **NOT DONE**

### Phase 3 — Planner / Tracker / Overview / Money Flow ⏸ NOT STARTED
- [ ] 3.1 Monthly planner (zero-based, PVA, metrics, populate-from-actuals, yearly overview)
- [ ] 3.2 Spend tracker (budget vs actual, categories, CSV/XLSX import, charts, AI categorization)
- [ ] 3.3 Account overview (banks/cards/PF + health) — OCR optional/deferred
- [ ] 3.4 Money flow (lent/borrowed/locked/uncounted)

### Phase 4 — AI & intelligence ⏸ NOT STARTED
- [ ] 4.1 Reuse Buckwheat AI client (`keyboard/VoiceAi.kt`, `data/categories/SpendCategorizer.kt`) for categorization, planner allocation, anomaly detection, monthly letter
- [ ] 4.2 AI Hub (chat w/ financial context, reports, forecast) — deferrable

### Phase 5 — Security, notifications, polish ⏸ NOT STARTED
- [ ] 5.1 PIN app-lock + biometric + auto-lock; AES-encrypted backups
- [ ] 5.2 Notifications (SIP/maturity/goal/digest/spend) via AlarmManager/WorkManager
- [ ] 5.3 Backup/restore/cleanup/quota + storage recovery
- [ ] 5.4 Themes (9 accents × auto/light/dark), compact layout, privacy mode, a11y

### Cross-cutting ✅ (per phase)
- [x] Golden pipeline `spotlessApply` + `testDebugUnitTest` + `assembleDebug` green after each phase
- [x] Unit tests for pure math (portfolio calc, SIP generator, tax, CSV codec)
- [x] `.track/` updates + commit + push per phase

---

## 5. Complete Commit History (branch `feature/trackinvest-migration`)

| Commit | Phase | Summary |
|---|---|---|
| `81df030` | ref | docs: add TrackInvest migration reference |
| `3d32732` | 0.1 | scaffold new module |
| `61b5b77` | 0.2 | Room schema v1 + Hilt + DataStore |
| `2ac6123` | docs | record Phase 0.1 + 0.2 |
| `69ac5b7` | 0.3 | CSV export/import + LedgerRepository |
| `afa464c` | docs | record Phase 0.3 |
| `15ee70b` | 1.1 | ledger CRUD UI + investment editor sheet |
| `7c8fc4f` | docs | record Phase 1.1 |
| `09ba576` | 1.2 | templates + recurring SIPs |
| `df413dd` | docs | record Phase 1.2 |
| `cf59c17` | 2 | dashboard portfolio phase + own copyright credit (skcode98) |
| `d71e5ea` | 2 | fix portfolio calculator test expectations |
| `85d8b66` | docs | record Phase 2 dashboard |
| `d47c447` | 2.3 | portfolio tab (donut, allocation, asset grid, maturities) |
| `bbe77bc` | docs | record Phase 2.3 portfolio tab |

**HEAD of archived branch: `bbe77bc`.** All commits preserved under tag `archive/trackinvest-migration`.

---

## 6. Module Architecture (as archived)

Module: `trackinvest/` — Gradle subproject, **no version catalog** (versions inline), KSP-only, minSdk 29 / target + compile SDK 36, AGP 8.7.3, Kotlin 2.2.0, Compose 1.8.3, Room 2.7.2, Hilt 2.57, commons-csv 1.14.0, core library desugaring.

```
trackinvest/src/main/java/com/danilkinkin/trackinvest/
├── Application.kt / MainActivity.kt
├── home/
│   ├── MainScreen.kt                 # NavigationBar: Dashboard / Ledger / Portfolio
│   ├── dashboard/
│   │   ├── Dashboard.kt              # full dashboard (hero, projection, monthly, trend, allocation, 80C, maturities, recent)
│   │   ├── DashboardCharts.kt        # custom Canvas LineChart/BarChart/DonutChart/ProgressRing/ChartLabelsRow
│   │   └── IncomeSheet.kt            # salary + New/Old regime editor sheet
│   └── portfolio/
│       ├── Portfolio.kt              # donut + allocation bar/legend + asset grid + maturity list
│       └── PortfolioViewModel.kt
├── ledger/
│   ├── Ledger.kt                     # Room list + export/import + FAB + empty state
│   ├── InvestmentEditorSheet.kt      # M3 bottom sheet add/edit
│   ├── InvestmentTypeChips.kt        # shared INVESTMENT_TYPES + FlowRow chips
│   ├── RecurringSipEditorSheet.kt / RecurringSipsSheet.kt
│   └── TemplateEditorSheet.kt / TemplatesSheet.kt
├── data/
│   ├── entities/ (Investment, Goal, RecurringSip, Template, Category, CategoryDetail, AllocTarget, MarketValue, Milestone)
│   ├── dao/ (9 DAOs: getAll LiveData, suspend CRUD, @Transaction deleteAllAndInsert)
│   ├── CsvCodec.kt                   # investmentsToCsv / csvToInvestments (RFC-4180)
│   ├── RecurringSips.kt              # pure SIP date logic
│   ├── Portfolio.kt                  # pure portfolio math (web port)
│   ├── LedgerViewModel.kt / RecurringViewModel.kt / DashboardViewModel.kt
├── di/
│   ├── TrackInvestDatabase.kt        # Room v1, fallbackToDestructiveMigration, exportSchema=true
│   ├── TrackInvestConverters.kt      # BigDecimal↔String, List<String>↔comma
│   ├── AppModule.kt                  # Hilt providers
│   ├── SettingsRepository.kt         # DataStore `trackinvest_preferences`
│   ├── LedgerRepository.kt / RecurringRepository.kt / PortfolioRepository.kt
├── backup/ (rememberExportCsv.kt / rememberImportCsv.kt)
├── ui/Theme.kt
└── util/numberFormat.kt              # formatAmount (US grouping)

trackinvest/src/main/res/
├── drawable/ (ic_add, ic_delete, ic_launcher_foreground, ic_tab_dashboard, ic_tab_ledger, ic_tab_portfolio)
├── values/ (strings.xml, colors.xml, themes.xml) + values-v31/themes.xml
└── mipmap-anydpi/ic_launcher.xml

trackinvest/schemas/com.danilkinkin.trackinvest.di.TrackInvestDatabase/1.json  (committed)
trackinvest/src/test/java/com/danilkinkin/trackinvest/  (4 suites, 47 tests)
```

### Room schema v1 — 9 entities
`Investment` (date, type, amount, note, tags, account, isMonthlyContrib, payoutType, interestRate, maturityDate, isClosed, ...), `Goal` (name, target, saved, linkedCategory), `RecurringSip` (type, amount, note, tags, account, nextRun, isActive, intendedDay), `Template` (type, amount, note, tags, account), `Category` (name, color, icon, is80c), `CategoryDetail` (category, key, value), `AllocTarget`, `MarketValue`, `Milestone`. DB name `trackinvest_db`, DataStore `trackinvest_preferences`.

---

## 7. What Was Implemented (per phase)

### Phase 0.1 — Scaffold
`trackinvest` module wired into the root build (`include(":trackinvest")`), app identity (`app_name` "TrackInvest", launcher), `MainActivity` + Compose `Theme` (M3, Buckwheat palette), `NavigationBar` with Dashboard/Ledger placeholders. Module-local vector drawables (repo has **no material-icons dependency**).

### Phase 0.2 — Room + Hilt + DataStore
9 entities + 9 DAOs mirroring the web's `db` object; `TrackInvestDatabase` v1 (`fallbackToDestructiveMigration`, `exportSchema=true`); Hilt `AppModule` (Room DB, DAOs, DataStore); `SettingsRepository` on `trackinvest_preferences`. Deferred tables (planner/spend/overview/money-flow/AI/notifications) intentionally omitted.

### Phase 0.3 — CSV backup/restore
`CsvCodec.kt`: header `Date,Type,Amount,Account,Note,Tags`, ISO dates, date-desc sort, RFC-4180 via commons-csv. `LedgerRepository.exportCsv()/importCsv()` (additive `insertAll`). Launchers `rememberExportCsv.kt`/`rememberImportCsv.kt` (Buckwheat launcher pattern, filename `InvestPro_<date>.csv`). 11 `CsvCodecTest` tests.

### Phase 1.1 — Ledger CRUD UI
`Ledger.kt` (Room `LazyColumn`, export/import buttons, FAB, empty state), `InvestmentEditorSheet.kt` (M3 `ModalBottomSheet`: type chips + editable type, amount w/ error, M3 `DatePickerDialog`, account/note/tags, monthly switch, Save/Delete), `LedgerViewModel` + `LedgerRepository` CRUD, `formatAmount`, `ic_add.xml`. 3 `NumberFormatTest` tests.

### Phase 1.2 — Templates + recurring SIPs
`RecurringSips.kt` pure logic (web ports: `nextMonthlyRun`, `advanceMonth(current, intendedDay)` clamping to `min(intendedDay, lengthOfMonth)` so 31st SIP = Jan 31 → Feb 28 → **Mar 31**; `processDueSip` → `SipRunResult`; `MAX_SIP_RUNS = 24`; generated investments get `isMonthlyContrib=true`, note `"<note> (Auto)"`). `RecurringRepository` (sips/templates LiveData + CRUD + `quickLog` + `processDueSips(): Int`), `RecurringViewModel`, shared `InvestmentTypeChips` (FD/PPF/PF/SIP/Liquid/Home/Cash/Stocks + FlowRow), SIP/Template editor + list sheets, `Ledger.kt` two-row toolbar + auto-process on launch (`LaunchedEffect`). 12 `RecurringSipsTest` tests.

### Phase 2 — Dashboard + portfolio math
- **`Portfolio.kt`** — pure Kotlin web port:
  - `strictValuation` per type: FD (quarterly/monthly/simple payout compounding), PF/PPF (monthly compounding, gov rates **PPF 7.1 / PF 8.15**), **SIP/Stocks = invested-amount fallback (live NAV deferred — user-approved)**, custom-rate types (monthly compounding), else invested. `initialBal` category detail becomes a virtual investment dated day-before-earliest so it accrues interest. **Deviation**: per-investment valuation, not per-type-aggregate like the web (noted at the time).
  - `computePortfolioSummary`: per-type totals (incl. `lastDate`), net worth, total invested, this/last-month invested, year invested, avg monthly, `tax80c` (via `isCurrentFY`), `MATURITY_WINDOW_DAYS=90`, `NET_WORTH_HISTORY_MONTHS=24`, `monthlyInvestedPoints`, `netWorthPoints` (web `renderNWChart` walk-back), `recentActivity`, `valuationErrors`.
  - `projectionPoints` (web `renderHeroProjectionChart`: `currentNW + avgMonthly×i`).
  - `isCurrentFY(dateMillis, today, fyStartMonth, zoneId)` — FY from DataStore `fyStartMonth`.
  - `calculateStrictTax(salary, regime, tax80c)` — FY 2024-25 slabs, 87A rebate, 4% cess, status strings "Tax Free (Rebate Limit)" / "Tax Free (Rebate 87A)" / "Setup Income in Settings".
  - `today = LocalDate.now(zoneId)`, `zoneId` param throughout (default `ZoneId.systemDefault()`).
- **`PortfolioRepository.combine()`** — `investmentDao + categoryDao + categoryDetailDao + fyStartMonth` → `computePortfolioSummary` with `.flowOn(Dispatchers.Default)`.
- **`SettingsRepository`** — added `salaryKey`/`regimeKey` + `getSalary/setSalary/getRegime/setRegime` (plus pre-existing `getCurrencySymbol`, `getMonthlyInvestmentTarget`, `getFyStartMonth`).
- **`DashboardViewModel`** — `DashboardUiState(summary, currencySymbol, monthlyTarget, salary, regime)` via `combine(...).asLiveData()`; `setSalary/setRegime/setMonthlyTarget`.
- **`Dashboard.kt`** — HeroCard (net worth, invested, P&L, this/last month, range chips 3/6/12/MAX default 6 + `LineChart`), ProjectionCard (slider 1–60, steps 58 + `BarChart`), MonthlyCard (`ProgressRing` vs monthly target), TrendCard (12-month bars), AllocationCard (donut + first 10 allocation rows), Tax80cCard (tap → `IncomeSheet`), MaturityCard (90-day window), RecentActivityCard. Empty ledger → empty state; `uiState == null` → spinner.
- **`DashboardCharts.kt`** — custom Compose `Canvas` charts: `LineChart`, `BarChart`, `DonutChart`, `ProgressRing`, `ChartLabelsRow` (no third-party chart lib).
- **`IncomeSheet.kt`** — M3 `ModalBottomSheet`: salary + New/Old regime `FilterChip`s.
- **Copyright change**: new `spotless/copyright-trackinvest.kt` → module files use `/* Copyright 2026, skcode98, All rights reserved. */` (not Danil's header); applied across the module; `About.kt`/app `strings.xml` credit updated.
- **Tests**: `PortfolioCalculatorTest.kt` — 21 JUnit-4 cases (FD payouts, PPF 7.1%, PF 8.15% → 13839, custom-rate, SIP/Stocks fallback, initialBal accrual, summary windows, netWorth/projection points, `isCurrentFY`, strict tax, 80C).

### Phase 2.3 — Portfolio tab
- `TypeValuation` gained `lastDate: Long?` (max per-type investment date, computed in `strictValuation` + `computePortfolioSummary`).
- `home/portfolio/Portfolio.kt` — `NetWorthCard` (donut 180dp centered on net worth + invested/P&L stats + `AllocationBar` stacked Canvas bar + `AllocationLegend` rows), `AssetGridCard` (2-col grid via `chunked(2)`; `TypeCard` = type / current value / P&L tag `+₹X` or `-₹X` colored / `Last: <ISO date>`), `MaturityCard` (all 90-day maturities).
- `PortfolioViewModel.kt` (summary + currencySymbol LiveData).
- `MainTab` enum + PORTFOLIO tab, icon selection switched to `when`, `ic_tab_portfolio.xml` pie-chart vector, `tab_portfolio`/`portfolio_*` strings.

---

## 8. Settings / DataStore keys (`trackinvest_preferences`)

`currencySymbol` (default `₹`), `fyStartMonth` (default 3 — April), `monthlyInvestmentTarget` (Double), `salary` (Double), `regime` ("new"/"old"). Access via `SettingsRepository` (suspend getters, `edit {}` setters, `Flow` for observation).

---

## 9. Design Decisions & Deviations from the Web App

1. **SIP/Stocks valuation = invested amount** for now; live NAV is deferred. The dashboard/portfolio therefore show principal for these types until a NAV source is wired up.
2. **Per-investment valuation** in `strictValuation` (web aggregates per type then applies the rate to the sum). Result differences are expected and noted.
3. **Custom charts on Compose Canvas** instead of Chart.js/SVG.
4. **No material-icons dependency** — all icons are module-local vector drawables + `painterResource`.
5. **M3 components** (`ModalBottomSheet`, `DatePickerDialog`) with `@OptIn(ExperimentalMaterial3Api::class)`.
6. **US-locale `NumberFormat` grouping** (`100,000`), not Indian (`1,00,000`) — matches Buckwheat's `util/numberFormat.kt`.
7. **JUnit 4 for module tests** — `kotlin.test` is NOT on the module test classpath.
8. **Room v1 with `fallbackToDestructiveMigration`** — schema churn during migration is acceptable; JSON backup path (web `restoreData`) is the future cross-version migration story.
9. **BigDecimal equality in tests** must go through a compareTo helper (`assertBig`) — `assertEquals(BigDecimal, BigDecimal)` is scale-sensitive (`0` vs `0.00`).
10. **`Investment.uid` / `RecurringSip.uid` / `Template.uid` are class-body `var`s** → `data class copy()` resets them to 0; re-assign via `.also { it.uid = … }`. `uid == 0` means insert vs update.
11. **DataStore name `trackinvest_preferences`** — separate from Buckwheat's store so both apps coexist.

---

## 10. Gotchas / Lessons Learned (port these into future phases)

- Gradle shell flakiness on Windows: use `.\gradlew.bat … --console=plain > "$env:TEMP\bw_test.log" 2>&1; "EXIT=$LASTEXITCODE"` then `Get-Content -Tail N` (direct 2>&1 piping gets killed).
- `spotlessCheck` reports UP-TO-DATE right after adding files — run `spotlessApply` on new/changed files first.
- Every new `.kt` needs the module copyright header (spotless auto-fixes via `spotlessApply`).
- `rg` is unavailable on Windows — use `grep`/`Select-String`.
- `CSVRecord.size()` is a method, not a property.
- Compiler session files under `.kotlin/sessions/*.salive` — never stage/commit.
- `ModalBottomSheet`/`DatePickerDialog` need `@OptIn(ExperimentalMaterial3Api::class)`.
- Kotlin top-level functions need imports even in the same package prefix — verify with grep after edits.
- Never commit secrets; never commit `build/` or `.kotlin/sessions/`.

---

## 11. Tests & Build Status (as archived)

**47 tests green, 4 suites** (`:trackinvest:testDebugUnitTest`):
- `CsvCodecTest` — 11
- `PortfolioCalculatorTest` — 21
- `RecurringSipsTest` — 12
- `NumberFormatTest` — 3

Golden pipeline: `.\gradlew.bat :trackinvest:spotlessApply` → `:trackinvest:testDebugUnitTest` → `:trackinvest:assembleDebug` — all BUILD SUCCESSFUL at `bbe77bc`.

---

## 12. What Is NOT Done (deferred / remaining)

- **Live NAV** for SIP/Stocks (currently invested-amount fallback).
- **Ledger UX**: search, type filter, sort, date range, multi-select batch delete, insights bar, swipe-delete + undo.
- **Per-type fields** in the investment editor: FD renew auto-close, smart preview / reverse unit calc, live MF/stock search, sipDay/broker/subCategory/growthRate/units.
- **Goals + FIRE sheet** (`Goal` entity exists with `linkedCategory`; web uses invested principal of the linked category as `saved`, monthly contribution from recurring of that type, forecast with growth rates `{SIP:0.12, Stocks:0.12, PPF:0.071, PF:0.0815, FD:0.07, Cash:0, Liquid:0.06}`), FIRE target, portfolio health score (AI).
- **JSON backup/restore** mirroring web `exportData`/`restoreData`.
- **Sankey / heatmap / mini-cards / smart advisor / AI Hub** (Phase 4).
- **Backtester / historical SIP generator**.
- **PDF wealth report**, account overview, money flow, spend tracker, monthly planner (Phases 3/5).
- **Notifications** (SIP/maturity/goal/digest), PIN/biometric app-lock, AES-encrypted backups, themes/privacy/a11y.

---

## 13. How to Resume (if ever)

1. `git fetch origin` then `git checkout -b feature/trackinvest-resume archive/trackinvest-migration`.
2. Read this file, then `.track/trackinvest.md` (migration reference w/ web line anchors) and `.track/CACHE.md` (module build commands).
3. Recommended next: Phase 1.3 **goal linkage** or Phase 2.4 **Goals + FIRE sheet** (Goal entity + `linkedCategory` already in schema); or mine the web's goals code at `app_part3.js:418-460` + `app_part2.js` goals CRUD.
4. Run the golden pipeline before/after any change.

---

## 14. Related Branches / References

| Branch / tag | Purpose |
|---|---|
| `master` | Back on Buckwheat improvement work (post-archive). |
| `backup/pre-trackinvest-2026-08-09` | Frozen pre-migration Buckwheat state @ `bc5e922`. Do NOT delete. |
| `archive/trackinvest-migration` | Full archived TrackInvest migration branch @ `bbe77bc`. |
| `our-fixes` | Buckwheat saved feature work. |
| `.track/trackinvest.md` (on archived branch) | Migration reference doc w/ web source anchors. |
| `.track/CACHE.md`, `.track/CHANGELOG.md`, `.track/MEMORY.md` (on archived branch) | Per-phase records of the migration. |
