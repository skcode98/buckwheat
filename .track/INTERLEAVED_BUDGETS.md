# Interleaved Budget Categories — Implementation Plan

> **Status**: PLANNED → pending data availability. Feature designed to consume your 6-month Transaction history as its calibration dataset.

## Rationale

Indian household budgets interleave: monthly groceries + quarterly medical + annual insurance are not three separate budgets—they're one budget system with overlapping periods. The existing budget system treats everything as a single daily-budget counter. This plan adds frequency-aware budget allocations that all feed the same daily allowance logic.

## Architecture Overview

```
Existing:          [daily budget] → changeDayAction() → spend tracking
                      ↑
                      └── only daily counter

Target:            [interleaved budgets]
                     ├── monthly: Groceries ₹6500
                     ├── quarterly: Medical ₹15000       (→ ₹5000/month equivalent)
                     ├── annual: Insurance ₹80000        (→ ₹6667/month equivalent)
                     ↓
                   [combined daily allowance] → changeDayAction() → spend tracking
                     └── each tx allocated against its category's remaining balance
```

## Phase 1: Core Infrastructure (8-10 hrs)

### 1.1 BudgetCategory Entity (Room)
- `id`, `name`, `baseAmount`, `frequency`, `startDate`, `autoAllocate`
- `BudgetFrequency` enum: `DAILY/MONTHLY/QUARTERLY/ANNUAL`
- Migration: `DatabaseModule` 14 → 15 (auto-migration of `spentFromDailyBudget` into new per-category buckets)

### 1.2 Budget Engine (pure logic, no Android deps)
- `interleaved/InterleavedBudget.kt`:
  - `effectiveDailyAllowance(categories, date)` → `Map<String, BigDecimal>` (monthly-equivalent ÷ 30)
  - `shouldRollPeriod(category, date)` → boolean (quarterly: every 90 days; annual: every 365)
  - `projectedExhaustion(category, currentSpent, remainingDays)` → `BigDecimal`
  - `categoryBudgetForTx(tx, categories)` → assigns tx to a category (by persisted tx.category first)

### 1.3 Repository Layer Changes
- `SpendsRepository`:
  - `addSpent`: after commit, route amount against category-specific remaining balance
  - `removeSpent`: reverse-category bookkeeping
  - `setBudget`/period-change: reset per-category counters on `shouldRollPeriod`
- New `BudgetCategoryDao`:
  - `@Query("SELECT * FROM budget_categories WHERE frequency IN ('MONTHLY','QUARTERLY','ANNUAL')") getActiveInterleaved()`
  - `updateRemainingBalance(categoryId, newBalance)`

## Phase 2: Settings UI (3-4 hrs)

### 2.1 SettingsRow
- New `TextRow` ("Interleaved Budgets", `ic_account_balance_wallet`) → opens sheet
- End badge: active categories count

### 2.2 Category Management Sheet
- `CategoryBudgetSheet`: LazyColumn
  - Rows: category name + frequency dropdown (DAILY/MONTHLY/QUARTERLY/ANNUAL) + amount `OutlinedTextField` (decimal) + toggle
  - "Add (+)" FAB → `AddCategoryBudgetDialog`
  - Default templates:
    1. **Monthly Essentials** (auto): Groceries, Fuel, Transport
    2. **Quarterly Big Tickets**: Medical, Clothing, Repairs
    3. **Annual Obligations**: Insurance, Maintenance

## Phase 3: Analytics Integration (3-4 hrs)

### 3.1 New Analytics Card
- `InterleavedBudgetCard`: placed after `SpendsBudgetCard`
  - Per-category progress bar (fraction = spent / baseAmount)
  - Velocity indicator ("at ₹45/day pace → 70% of quarterly budget in 12 days")
  - Color-coded: green < 60%, amber 60-80%, red > 80%

### 3.2 Update Analytics.kt
- Observe `BudgetCategoryDao.getActiveInterleaved().asLiveData()` via `@HiltViewModel` factory
- Pass `categories` + current totals into `InterleavedBudgetCard`

## Phase 4: Data Calibration (Deferred – PENDING YOUR 6-MONTH DATA)

> **When you provide your transaction export**, I will insert:
> 1. **Pattern miner** (`interleaved/AnalyzeSpendingPatterns.kt`):
>    - Group transactions by category over the 6-month span
>    - Infer frequency: appears in 4-6 months → MONTHLY; every 3 months → QUARTERLY; once/year → ANNUAL
>    - Compute median monthly amount per category → auto-suggested `baseAmount`
> 2. **Backfill**: create `BudgetCategory` rows for every detected pattern
> 3. **Validation harness**: `InterleavedBudgetTest` (15 tests — projected vs actual spend, period roll correctness, velocity accuracy)

## Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Multiple categories assigned to one tx | High | `tx.category` persisted value wins; fallback: first matching category alphabetically |
| Period roll miscalculate (leap year, etc.) | Medium | Use `ChronoUnit.DAYS` + anchor day-of-month from `startDate` |
| Daily allowance overflow/underflow | Medium | Clamp aggregate daily to existing `dailyBudget ± 20%` to avoid jarring UX shifts |

## Dependencies
- Hilt (`@HiltViewModel` for the sheet)
- Room (migration 14→15, new Dao)
- Notifications (reuse `OverspendingNotifier` pattern)
- Kotlinx-datetime already in deps (no new libraries)
