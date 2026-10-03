# Family Sync Redesign — Design Spec

**Date:** 2026-10-03
**Status:** Draft for review
**Supersedes:** `2026-09-26-family-sync-design.md`, `2026-10-01-family-budget-allocation-design.md`

## Why

The family sync feature shipped with a shared-pool model built around a `head` / `owner` role. In
use it does not work, and three symptoms were reported: sync appears dead, other members cannot see
the head's spending, and the model is not understandable.

Root causes, all confirmed by reading the code rather than by guessing:

1. **Other members' transactions are written into the local `transactions` table.**
   `RoomSyncDatabase.loadRecords` is `gateways.flatMap { it.loadAll() }` (`RoomSyncDatabase.kt:32`)
   and `apply()` inserts whatever the server returns with no member filter (`RoomSyncDatabase.kt:89-96`).
   Every personal read is unfiltered: `getAllSpends()` (`SpendsRepository.kt:117`), `getAllTags()`
   (`:123`), `getAllCategories()` (`:135`), `getSpendsInRange()` (`:120`), History, analytics, the
   weekday breakdown. The headline wallet figure survives only because it comes from DataStore via
   `addSpent`, which is exactly why this read as "sync is broken" instead of "someone else's rent is
   in my history".

2. **The roster is only ever fetched from one screen.** `FamilySyncRegistrar.persist()` clears
   `FamilyMembersCache`; the only refill is a collector in `FamilySyncViewModel.init` (~line 86), a
   ViewModel scoped to the settings sheet. A member who never opens that sheet has an empty roster
   permanently, so `FamilyBudgetViewModel.members` is empty, `rollupPersonalSpend` has no names to map
   onto `member_id`, and allocations render as raw UUIDs.

3. **The head's household spend is hidden by default.** `householdDetailVisibleToAll` defaults to
   false (`FamilyBudgetViewModel.kt:418`) and the sheet gates household rows on
   `isHead || detailVisible`, so members see the household total and never a line item.

4. **Sync only looks dead.** Transport is correct — `addSpent` queues (`SpendsRepository.kt:643`),
   the payload carries no family key because the server derives it from the token, and
   `SyncEngine.runSync()` pushes and pulls in one pass. The perception comes from feedback defects:
   `FamilySyncSheet` snapshots `status()` instead of observing it, `enqueueUniqueWork KEEP` swallows a
   second tap while the toast claims a sync happened, and `NotEnrolled` returns silent success.

The pool model itself is also the wrong shape for this app. Buckwheat is a personal budget tracker;
making a family share one pool of money requires a role, an editor, an allocation rule, a privacy
flag and a consent workflow before a user can see anything.

## Decisions

Taken with the user, in this order.

| Decision | Choice |
|---|---|
| What a member sees | **Full transparency — every member sees every other member's spending.** No head/owner role. |
| Money model | **Each person keeps their own budget**, their own periods, their own limits. Family mode adds visibility only. |
| Surface | **One `Family` sheet opened from Settings.** |
| History depth | **Current budget period only.** |
| Storage boundary | **Separate `family_transactions` table** for remote rows. |
| Categories / tags | Local per person. A shared list is a conflict surface for no gain. |
| Spend assignments ("I paid for X") | Dropped. Under full transparency the transaction is already visible. |
| Invite codes | The `/invite` mint-a-code ceremony is dropped; `create` already returns a join code and there is no seat limit for it to gate. |
| Departing members | Their transactions stay. Token revoked, marked departed, shown collapsed. |

## Non-goals

- Shared budgets, allocations, or any per-member spending limit.
- Historical months of another member's spending.
- Syncing categories, tags, recurring templates, savings goals, budget periods, or archives.
- Any head/owner/permission concept.

## 1. Data model

### 1.1 What crosses the network

Exactly one table: `transactions`.

Local-only from now on: `archived_transactions`, `budget_periods`, `saved_categories`, `saved_tags`,
`recurring_templates`, `savings_goals`.

Dropped, not merely unsynced: `family_state`, `period_limits`, `spend_assignments`.

`Transaction` needs **no new columns**. The existing `family_id`, `member_id`, `sync_seq`, `version`,
`updated_at`, `deleted_at` already carry the whole model — `member_id` says whose spend it is,
`family_id` says whose family, `sync_seq` and `version` order the changes.

The `budget_periods` question resolves cleanly: **`Transaction` has no `period_id` at all**
(`Transaction.kt:42-99`). A family view is "rows whose `date` falls in the viewer's current period,
grouped by `member_id`". `ArchivedTransaction` is the only entity with a hard `period_id` foreign key
to `BudgetPeriod` (`ArchivedTransaction.kt:11-21`), and archived rows are not synced, so the
cross-device period-reference problem does not arise and the archived FK 500 goes with it.

### 1.2 `family_transactions`

New table, same shape as `transactions` minus the columns that only ever described a local budget
cycle, plus everything sync needs:

`id` (PK), `type`, `value`, `date`, `comment`, `category`, `member_id`, `sync_seq`, `updated_at`,
`deleted_at`, `version`.

Not carried: `family_id` (implied — the table only ever holds one family, and the session asserts it),
`bucket`, `assignment_id`, `assigned_by_member_id`. `RoomSyncDatabase.reset()` empties it on leave or
sign-out, so the table can never hold rows from a previous family.

Keeping remote rows in a separate table makes the invariant structural rather than disciplinary:
**enrolling in a family cannot change what your own wallet, history, tags, categories or analytics
show.** No personal query needs a member filter, because no personal query can see these rows.

`SyncTableBinding` already separates the wire table from the loader/inserter, so the binding for the
one synced table points at `family_transactions` rather than `transactions`. Wire format unchanged.

### 1.3 Migration (Room version 22)

- `CREATE TABLE family_transactions` per §1.2, with indices on `member_id`, `date`, `type`.
- `DROP TABLE family_state`, `period_limits`, `spend_assignments`.
- Move existing remote rows: at first launch after upgrade, rows in `transactions` whose `family_id`
  is set and whose `member_id` differs from the signed-in member are copied into
  `family_transactions` and deleted from `transactions`. This cannot be SQL in the migration because
  the member id is not known at migration time, so it is a runtime step in `RoomSyncDatabase`, run
  once and guarded by a flag.
- Attribute orphaned local rows: rows with `bucket = 'HOUSEHOLD'` and a null `member_id` are copied to
  `member_id = <signed-in member>`. The old head-only flow is being deleted, so these rows have no
  other author.
- `bucket` stays on both `transactions` and `archived_transactions`. Dropping it is a destructive
  migration for no user-visible gain, and `archived_transactions` has no bucket-dependent query left
  after this change. It becomes an unread column.

### 1.4 Sync contract

`POST /v1/sync` keeps its push-plus-pull-with-cursor shape. Changes:

- **The change set can only contain `transactions`.** Eight of the nine table branches are deleted.
- **`family_id` is never read from the payload.** The server stamps it from the bearer token. A
  forged family id becomes a no-op rather than a cross-family write.
- **Pull accepts an optional `since` (epoch millis).** The client sends its own current-period start,
  so a phone joining today does not pull a decade of history.
- **Auth collapses to two rules on the single table:**
  - insert/upsert — the row's `member_id` must equal the token's member, or be absent (a new row is
    stamped with the caller)
  - delete — only the row's author
- `FAMILY_GOVERNED_TABLES` is deleted. With one table the concept has no meaning.

`SyncMerge.resolve` and `SyncEngine.settle()` are table-agnostic and are not modified. This change
deletes inputs to them, not logic.

## 2. Server and client wiring

### 2.1 Server (`server/src/main/kotlin/family/sync/`, `family/`)

| File | Change |
|---|---|
| `SyncStore.kt` | 9 table specs → 1. Delete `FAMILY_GOVERNED_TABLES`. Collapse the 5 `storedFacts` overloads to one. Replace `authorize()` with the two rules in §1.4. `write()` stops rebinding `member_id` on the caller's behalf and stamps both `family_id` and `member_id` from the token. |
| `PayloadValidation.kt` | Delete `BUCKETS` and `ASSIGNMENT_STATUSES`. Keep type, value, numeric and UUID checks. |
| `SyncContractExport.kt` | One table in the export. `SCHEMA_VERSION` 2 → 3. |
| `SyncRoutes.kt` | Accept and pass through the `since` pull parameter. |
| `FamilyRoutes.kt` | Delete `/invite`. Delete the route-level token revoke in `/leave` — `FamilyStore.leave` already revokes in-transaction, and doing it twice outside the transaction leaves a window where a crash leaves a live member row with a dead token and no re-auth path. |
| `FamilyStore.kt` | Delete `isOwner` and the promotion block. The `is_owner` column stays in the schema, unread, rather than spending a migration on it. |
| migrations | Drop `family_state`, `period_limits`, `spend_assignments`. |

Kept: `POST /v1/family/create`, `/join`, `/members`, `/whoami`, `DELETE /v1/family/leave`.

### 2.2 Client sync (`sync/`)

- `SyncTables.kt` and `SyncBindings.kt`: one binding, `payloadOf`/`metaOf`/`decoder` for
  `transactions`, inserter/remover pointed at `family_transactions`.
- `SyncPayloads.kt`: transactions only. `bucket`, `assignment_id`, `assigned_by_member_id` are no
  longer sent.
- `RoomSyncDatabase.kt`: `apply()` and `enrolAll()` lose all dropped tables. `enrolAll` gains the
  one-time §1.3 re-homing of remote rows.
- `SyncDirtyMarker` callers stop marking dropped tables. `SpendsRepository`'s
  `markUpsert(TRANSACTIONS)` on `addSpent` is unchanged and still required.

### 2.3 Roster

**`SyncEngine.runSync()` calls `api.members()` after a successful pull and writes
`FamilyMembersCache`.** The collector in `FamilySyncViewModel.init` is deleted.

This is the fix for cause 2. Roster freshness stops depending on a particular screen having been
opened. The Family sheet additionally triggers `syncNow` when the roster is empty and a session
exists, so a first open is never permanently nameless.

### 2.4 Sync feedback

`FamilySyncSheet`'s unconditional "sync started" toast is deleted. `SyncStatusChip` already observes
the real WorkManager running state, so the button shows a spinner until the run settles and the chip
reports `Syncing…` / `Synced N min ago` / `Offline · N pending`.

### 2.5 Departure

`FamilyStore.leave` deletes the member's tokens and the member row. Their transactions stay in the
remaining members' `family_transactions` untouched — that is what the "Former members" section reads.
Only the departing device's own copy is cleared, by `RoomSyncDatabase.reset()`. Their token is
revoked, so they can never push again.

### 2.6 Offline-first, unchanged

The Family sheet reads Room and never the network. It paints last-known data immediately and updates
when a sync lands. This is why the perceived deadness had no other feedback surface to fall back on.

## 3. UI

Two new files replace eight.

**Added:** `family/FamilySheet.kt`, `family/FamilyViewModel.kt`, `family/MemberDetailSheet.kt`.

**Deleted:** `FamilyBudgetSheet`, `FamilyBudgetViewModel`, `FamilyBudgetMath`, `MemberTagEngine`,
`FamilyInsightService`, `FamilySnapshot`, `SpendAssignmentLogic`, `SpendAssignmentsViewModel`,
`FamilySyncSheet`, `FamilyMembersSection`.

**Kept:** `FamilySyncViewModel` (connect/join logic, minus the roster collector), `SyncStatusChip`,
`SyncConflictsSheet`.

**Also deleted:** the `memberNames` lookup in `History.kt:77-81`. History is yours only now.

### 3.1 Entry point

Settings gets one always-visible **Family** row opening `FAMILY_SHEET`. The current
`if (enrolled)` gate on the budget row (`Settings.kt:215`) is what created the stranded path where
opening the sheet directly told you that you were not enrolled, with no way forward.

### 3.2 One sheet, two states

**Not connected** — the connect form, inline: server URL, display name, Create family, Join with code.
Logic already exists in `FamilySyncViewModel`.

**Connected** — the dashboard.

There is no third state in which the sheet renders a dead end.

### 3.3 Connected layout

1. **Sync chip** — live state.
2. **Family total** — combined spend this period across all members, the viewer's own share called
   out beneath it, and the period date range as a caption.
3. **Member rows** — viewer first, then by spend descending. Initials avatar coloured by hashing the
   member id, name, a `You` badge, spent-this-period as the hero figure in the app's currency format
   (`numberFormat(..., trimDecimalPlaces = true)`, the same path every other screen uses), then daily
   average, transaction count and last-active as the sub-line. Tap opens the detail sheet.
4. **Departed members**, collapsed, when any exist.
5. **Leave family** behind a confirmation dialog, plus disconnect.

### 3.4 Member detail

Header with the member's name and period total, then their transactions grouped by day, reusing
History's existing `DayCard` and transaction-row composables so the screen looks like the app already
in use rather than a bolted-on one.

### 3.5 Budgets are not shown for other members

`budget_periods` is local-only (§1.1), so another member's budget is genuinely not on this device. The
viewer's own row shows their own budget because it is local. The sub-line says this outright rather
than leaving the absence looking like a bug.

### 3.6 States

Each of these was wrong or missing in the old UI:

| Situation | Behaviour |
|---|---|
| Not connected | Connect form. Never a dead end. |
| Solo family, roster loaded | "You're the only member so far" plus a copyable join code. |
| Roster loading | Two skeleton rows, not a blank screen. |
| Roster fetch failed | Cached names still render, with a Retry affordance and a "couldn't refresh" note. |
| Zero spend in window | "Nothing spent this period." |
| Join / create rejected | Inline under the offending field, not only a snackbar. |
| Sync failing | Chip shows the error state; the sheet keeps showing last-known data. |

Every composable gets an `@Preview`. The old family code had none.

### 3.7 Home screen

Untouched. The wallet screen, its daily budget, its rest figure and History are yours alone, by
construction.

## 4. Testing

### 4.1 Server

- Push as a member writes a row; the stored `family_id` and `member_id` come from the token even when
  the payload forges them.
- A member pushing a row that names a different `member_id` is refused `cross_member_write`.
- A member deleting another member's row is refused.
- The author's own update and delete succeed.
- `since` bounds the pull window.
- `SyncContractExportTest` asserts one table and `SCHEMA_VERSION == 3`.
- Contract tests assert **values and types**, not key sets. The previous contract test compared key
  sets only, which is why `"pool_0"` sat in fixtures for weeks against a UUID-typed column and would
  have 400'd on push.

### 4.2 Client

- `SyncEngineTest`: a pull inserts remote rows into `family_transactions` and **never into
  `transactions`**. This is the regression test for cause 1 and the most important test in the change.
- `FamilyViewModelTest`: member rows render names from the roster; with an empty roster the sheet shows
  skeleton state, never UUIDs.
- `FamilyViewModelTest`: family total equals the sum of member spends, and the viewer's own share is
  correct.
- `MemberDetailSheetTest`: tapping a member lists their transactions in the window and nothing outside
  it.
- `Migration21To22Test`: remote rows are re-homed, orphan household rows are attributed, and the three
  dropped tables are gone.
- An end-to-end test: two `SyncDatabase` instances with two sessions push and pull, and each side's
  local `transactions` table contains only its own rows.

### 4.3 Pre-existing failures, unchanged

`AppLockViewModelTest`, `PatternEngineTest`, `CategoryCapsTest`, `RecurringDueDedupTest` and
`RecurringPaymentsSheetTest` were already red before this change, reproduced with the working tree
untouched. `AppLockViewModelTest` deadlocks in teardown (`runTest` on the Robolectric main thread
against a DataStore `edit`). They are not in scope and must not be used as a signal for this work.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Re-homing rows at first launch touches user data | Guarded by a one-time flag, runs inside `RoomSyncDatabase`, covered by `Migration21To22Test`. Rows are copied before being deleted. |
| A member's device is on an old app version | The server accepts only `transactions` changes from new clients. Old clients push nine tables and get refused, which is a visible error rather than silent divergence. Server keeps its tables until the fleet has migrated. |
| `sync-contract.json` is gitignored and `app/src/test/resources/sync-contract.json` no longer exists | The previous spec's "regenerate both copies" instruction was stale. The new spec pins contract assertions in code instead. |
| Deleting eight files loses work worth keeping | `MemberTagEngine` and `FamilyInsightService` are the only ones with real logic. Both are recoverable from git history if a later design wants them. |

## 6. Open questions

None. All design forks were resolved with the user during this spec.
