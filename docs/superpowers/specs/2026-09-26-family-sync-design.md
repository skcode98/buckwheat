# Family Sync Design

**Date:** 2026-09-26
**Status:** Approved design, pending implementation
**Classification:** Architectural (new subsystem)

## Goal

Let a family share one budget pool with per-member sub-budgets and full transaction history across all their devices. The app stays usable with no network at any point.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Backend | Custom backend on Render, data in the owner's Supabase Postgres | Maximum control; Supabase is already provisioned and its free tier retains data |
| Budget model | Per-member sub-budgets inside one shared pool | Accountability per person, which the shared-collective intent needs |
| Identity | Invite code from an existing member, no passwords | No password storage, no reset flow, no email backend |
| Privacy | Full transparency, every member sees all spending | Keeps the sync endpoint unfiltered; cheapest correct choice |
| Sync scope | All seven existing tables | Devices should end up identical |
| Conflicts | Last write wins, with a conflict notice to the stale writer | Predictable; nothing is silently invisible to the user |
| Periods | Per-member limits stored per period, no rollover | Mirrors existing single-budget behaviour, no new rollover accounting |
| Mechanism | Delta sync, push then pull | Only option that is correct offline and survives a sleeping host |

## Non-Goals

- No member-to-member private spending. Full transparency is a deliberate decision, not an oversight.
- No rollover of unspent sub-budget.
- No realtime streaming. Delta catch-up is the base; realtime would be an optimisation on top.
- No account recovery for a family where every device is lost. A printed recovery key is out of scope for v1 and is a known limitation.
- No sync of AI keys or app-lock secrets. Those stay local, matching the existing backup exclusion.

## Architecture

Three components.

### Android app (this repository)

Keeps Room as its local source of truth and is fully functional offline. Gains a `sync/` package, a family/member surface in settings, and per-member sub-budget display on the home screen. The read path every screen already uses keeps returning the same `Flow` types.

### Backend on Render

A small Kotlin/Ktor service in `server/`, built as its own Gradle build so it stays out of the Android build. It holds the Supabase service-role key and is the only component that touches the database. Its responsibilities are narrow and deliberately free of budgeting logic:

- create a family and its owner
- mint and redeem single-use invite codes
- mint and verify member tokens
- assign sequence numbers
- enforce last write wins
- scope every query to the requesting member's family
- reject any request whose token family does not match the requested record family

### Supabase Postgres

Mirrors the app's tables, one row per synced record, each carrying `family_id`, `seq`, `updated_at`, `version`, and `deleted_at`. A single Postgres sequence backs `seq` across all tables so one cursor covers an entire family. The database is a meeting point, not a second source of truth.

## Identity Model

The first device to enable family sync creates the family and becomes its owner. That device displays an invite code that is single use and expires after 15 minutes. A new device enters the code; the backend verifies it and returns a long-lived member token, stored in a new DataStore file. The token identifies the member on every request.

There are no passwords anywhere in the system, so there is nothing to leak and no reset flow to build. Tokens are 32 random bytes, stored server-side only as a hash, and scoped to exactly one member in one family.

### Existing data at enrolment

`Transaction` gains a member id, but existing rows have none. At enrolment they are attributed to the member enabling sync, who is the owner. No history is lost at setup.

## Data Model

### Room migration 16 to 17

Sync columns added to all seven synced entities:

```
family_id TEXT NULL      -- null means not yet enrolled in a family
sync_seq  INTEGER NOT NULL DEFAULT 0
updated_at INTEGER NOT NULL DEFAULT 0
deleted_at INTEGER NULL
version   INTEGER NOT NULL DEFAULT 1
```

All identifiers are UUIDs, carried as strings on the wire and stored as `TEXT` locally. The server's `seq` is a 64-bit Postgres `bigint` drawn from one shared sequence, so it is held locally as `Long` in the pending-mutation table rather than `Int`, to avoid a silent overflow at roughly 2.1 billion changes.

`Transaction` and `ArchivedTransaction` additionally gain `member_id TEXT NULL`.

`family_id` is nullable so an existing single-user install keeps working with sync off. That is the single most important compatibility decision in this design: a user who never enables family sync must see no behavioural difference.

### New entities

- `FamilyState` — one row per family holding `budget`, `startDate`, `finishDate`, `currency`. This data currently lives in DataStore and is relocated out. See Budget Recompute.
- `Member` — `id`, `familyId`, `displayName`, `isOwner`, `joinedAt`
- `PeriodLimit` — one row per member per `BudgetPeriod` holding that member's sub-budget for that period

### New local tables

- `SyncCursor` — per-device cursor, last successful sync time, in-flight flag
- `PendingMutation` — locally dirty records awaiting push, so offline writes survive process death

## Sync Protocol

Three endpoints.

### `POST /v1/family/create`

Creates a family and its owner member. Returns the owner's member token and the family id.

### `POST /v1/family/invite`

Owner only. Mints a single-use invite code with a 15-minute expiry. Returns the code and its expiry.

### `POST /v1/family/join`

Redeems an invite code. Returns the caller's member token and the family id. Fails on a used, expired, or unknown code.

### `POST /v1/sync`

The workhorse. Request carries the member token as a bearer header plus:

- the device's last known `cursor`
- the set of records it has changed, each with its local `version` and `updatedAt`
- the set of record ids it has deleted

Response carries:

- a new `cursor`
- the ids of records the server accepted
- every record in the family whose `seq` exceeds the request cursor
- a list of `ConflictNotice` entries for records where the device's write lost

The client applies the response inside a single Room transaction. A timed-out request is safe to repeat: every write carries the version the device started from, and re-pushing an already-accepted record is a no-op rather than a duplicate.

There is no long-lived connection. This is what makes the design work on Render's free tier, where the service sleeps after 15 minutes idle.

## Conflict Handling

Each record carries a `version`. On write:

1. If the incoming `version` matches the stored version, the write is accepted and the version increments.
2. If the incoming `version` is stale, the write is accepted anyway if its `updatedAt` is newer (last write wins), the record is overwritten, and a `ConflictNotice` is returned naming the record and the member who won.
3. If the incoming `version` is stale and its `updatedAt` is older, the write is rejected, the server's version is returned unchanged, and a `ConflictNotice` is returned.

The rule that matters: a conflict is never invisible. The losing device always learns about it and surfaces a snackbar.

Tombstones always win over live records, regardless of `updatedAt`, so a delete on one device cannot be undone by a stale write from another.

## Budget Recompute

This is the largest refactor in the project and the one that most changes existing behaviour.

Today `SpendsRepository` maintains `spent`, `spentFromDailyBudget`, and the daily budget as incrementally-mutated DataStore counters. That model is incorrect with more than one device, because two devices incrementing the same counter produce a total that matches nothing anyone spent.

The design splits inputs from derived state:

- **Inputs sync.** The budget amount and period dates become a synced `FamilyState` record.
- **Totals derive.** `spent`, the daily budget, and per-member rollups are recomputed locally from the synced transaction set rather than incremented in place.

`SpendsRepository` keeps exposing the same `Flow` types, so the read path is unchanged and callers are unaffected. Only the mechanism behind the flow changes.

The pool is defined as the sum of that period's `PeriodLimit` rows, so the pool and the member slices are reconciled from one number rather than two competing ones. Setting a period therefore requires the member limits to sum exactly to the pool; a mismatch is a validation error surfaced in the editor rather than a silently stored inconsistency. A partially allocated pool, where limits sum to less than the total, is not a v1 concept.

## Failure Handling

Every failure mode is non-fatal. A budget tracker must never refuse to log a coffee because a server is down.

| Condition | Behaviour |
|---|---|
| Offline | Writes queue in `PendingMutation` and flush on the next successful run |
| Server unreachable | Sync fails silently in the background; the UI shows last-synced time |
| Host asleep | First sync after idle takes about a minute on Render free; the UI shows a syncing state |
| Conflict | Dismissible snackbar naming the record and the winning member |
| Token revoked or expired | "Your family access expired, re-join with a new code" |
| Invite code expired or used | Explicit message on the join screen, distinct per case |
| Partial push | Accepted ids are recorded, rejected and conflicted records are surfaced, cursor is not advanced past failures |

Background sync uses WorkManager with exponential backoff and `Result.retry()` on transient failure. `AppLockViewModelTest` is known to hang `:app:testDebugUnitTest` and must be excluded from every run.

## Testing Strategy

No emulator is available on the development machine, so everything testable is made a pure function over plain data classes.

- `SyncMergeTest` — newer remote wins, newer local produces a conflict notice, tombstone always applies, cursor advances, empty remote
- `BudgetRecomputeTest` — same-day spend, older-day spend redistributing the daily budget, out-of-period spend excluded, per-member rollups equal the overall total, empty input
- `PeriodLimitTest` — limits reset on a new period, no rollover
- `Migration16To17Test` — Robolectric `MigrationTestHelper` against the exported `app/schemas`, inserting v16 rows then migrating and asserting data survived and new columns default correctly
- `SyncEngineTest` — fake `SyncClient` asserts push-then-pull ordering and that a push failure leaves the cursor untouched
- `SyncWorkerTest` — uses `work-testing`, already a dependency
- `ConflictPolicyTest`, `InviteRedeemTest`, `FamilyScopeTest` — server-side

Server tests cover token issuance, invite redemption, last-write-wins resolution, and family scoping, including a valid token being refused access to another family's record and a tampered token being refused.

## Deployment

A `render.yaml` blueprint and a multi-stage `server/Dockerfile`, Gradle build then JRE 17 runtime. Supabase URL and service-role key are Render environment variables. The service-role key is never in the app and never committed. Health check points at `GET /health`.

Render's free tier is acceptable because all durable data lives in Supabase. The service sleeps after 15 minutes idle and takes about a minute to wake; upgrading to the Starter plan removes that and the design needs no change to allow it.

MongoDB Atlas was explicitly rejected: its free M0 tier deletes clusters after 30 days of inactivity, which is unsafe for financial data.

## Phasing

This is several subsystems. One monolithic plan would be unreviewable.

1. Backend service, Supabase schema, family create/join/invite — verifiable with `curl` alone
2. Room migration 16 to 17 and the sync engine, transactions only
3. Budget relocated to `FamilyState`, the recompute refactor, per-member limits
4. Remaining tables: periods, categories, tags, recurring, goals, archived
5. UI surfaces, WorkManager background sync, conflict notices
6. Render deployment and documentation

Phases 1 and 2 produce a working two-device transaction sync. Phases 3 through 6 add the budget model and polish.

## Known Risks

- **Two-device round trip is unverified in development.** It needs two real phones and a deployed backend, so it is a manual acceptance step, not an automated test. This is the most likely place to be surprised.
- **The `SpendsRepository` recompute refactor touches the path every screen reads from.** It is gated behind having pure, well-tested recompute functions first.
- **Soft deletes plus the `period_id` cascade delete can resurrect rows** if tombstones and cascades interact badly. Phase 4 needs explicit merge tests for archived transactions and imported period buckets.
- **A family that loses every device cannot recover.** Accepted for v1 given there are no passwords.
