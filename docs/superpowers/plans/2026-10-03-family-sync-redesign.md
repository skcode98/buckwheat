# Family Sync Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse family sync down to one shared table (`transactions` on the server, a new local `family_transactions` mirror on the device), drop the head/owner role and the invite ceremony, keep departing members as read-only rows, and replace the two existing family sheets with one always-reachable **Family** sheet showing the whole family's spend for the viewer's current period.

**Architecture:** Server keeps its `transactions` table as the only synced table and stamps `family_id`/`member_id` from the bearer token instead of trusting the payload; the client syncs into a *separate* local table `family_transactions` so a pull can never overwrite the viewer's own `transactions`. The client roster comes from one `GET /v1/family/members` refresh performed by `SyncEngine` after a successful sync. All UI reads `family_transactions` filtered by the viewer's current period bounds.

**Tech Stack:** Kotlin 2.2.0, Room 2.7.2 (DB v21 → 22, manual migration), Hilt 2.57, Jetpack Compose, DataStore Preferences, Coroutines/Flow; server Kotlin JVM + Ktor 3.6.0 + PostgreSQL (Flyway migrations V6 in Task 1, V7/V8 in Task 2), JUnit 4 + `ktor-server-test-host` + `io.zonky.test:embedded-postgres`.

**Spec:** `docs/superpowers/specs/2026-10-03-family-sync-redesign-design.md`

## Global Constraints

- Room database version goes 21 → 22. `Migration21to22` is declared in `app/src/main/java/com/danilkinkin/buckwheat/di/DatabaseModule.kt` (top-level `val`, same shape as `Migration20to21`) and appended to `DatabaseModule.MANUAL_MIGRATIONS`. There is no `data/AppDatabase.kt` and no `data/migrations/` package.
- `exportSchema = true`: the first build after the version bump generates `app/schemas/com.danilkinkin.buckwheat.di.DatabaseModule/22.json`. That file must be committed, not gitignored.
- Every DAO write path uses a hand-written `INSERT … ON CONFLICT(id) DO UPDATE SET` listing **every** non-key column. Never `@Insert(REPLACE)`, never `@Upsert`.
- Sync wire table names live in two places that must stay in sync: `app/…/sync/SyncTables.kt` and `server/src/main/kotlin/family/sync/SyncStore.kt` (`object SyncTables.ALL`). `SyncPayloadContractTest` cross-checks them by scraping the server source, so never change one without the other.
- `SyncContractExport.responseFields` scrapes `SyncRoutes.kt` by splitting on the literal markers `putJsonArray("accepted")`, `putJsonArray("records")`, `putJsonArray("conflicts")`. Keep those three literal strings intact.
- Server migration numbering is sequential and Flyway-verified. Task 1 lands `V6__relax_comment_nullability.sql` (drop `not null` on `transactions.comment`) because the single-table `TableSpec` treats `comment` as nullable and a commentless payload must not bind NULL into a `not null` column. Task 2 then lands `V7__family_departed_at.sql`, which is **additive only** (`members.departed_at` + its index), and puts all destructive drops in `V8__drop_family_governance.sql` so older app builds keep working.
- `transactions.comment` is optional on the wire. Absence binds NULL; do not coalesce to `''` on write and do not reject the payload with a 400 — both hide a real transformation from the client.
- Server test command is `.\gradlew.bat -p server test` **from the repo root** (there is no wrapper inside `server/` and no `gradle` on `PATH`). Client test command is `.\gradlew.bat testDebugUnitTest --tests "com.danilkinkin.buckwheat.<pkg>.<Class>"`.
- Never run two Gradle builds at once. Gradle must be launched detached and polled (see Task 0 helper), never in a blocking foreground call.
- Pre-existing red tests, out of scope, must not be treated as signal: `AppLockViewModelTest`, `PatternEngineTest`, `CategoryCapsTest`, `RecurringDueDedupTest`, `RecurringPaymentsSheetTest`, `RecurringChargeConfirmSheetTest`.
- **Every task leaves the tree compiling and green.** Room 22 forces `FamilyState`/`PeriodLimit`/`SpendAssignment` out of the entity list, and their last two consumers are the family ViewModels — so Task 3 deletes the whole family UI layer *before* Task 4 touches the schema. Do not reorder these two tasks, and do not accept a commit that fails `compileDebugKotlin`. One drift is expected and allowed: from Task 1 until Task 4, the app module's `SyncPayloadContractTest` is red because it asserts the server spec size equals the client's `SyncTables.ALL`, and the server collapses to one table while the client still declares seven. That is the contract test truthfully reporting that the client has not caught up; Tasks 1–3 must not touch the app module to silence it, and Task 4 closes it.
- UI strings come from `app/src/main/res/values/strings.xml` via `stringResource(R.string.*)`. No hardcoded user-visible text in composables. Icons must reference an existing `ic_*` drawable — reuse `ic_share`, `ic_arrow_right`, `ic_balance_wallet`, `ic_close`; verify with a drawable lookup before adding a new one.
- ViewModel convention in this repo is a mix; `FamilySyncViewModel` and `SyncStatusViewModel` already use `StateFlow`, so new family ViewModels use `StateFlow` too. Never `runBlocking`, never `!!`, always `as? T` + Elvis.
- Adding a method to `SyncStateStore` means implementing it in `internal object NoopSyncStateStore` (`sync/SyncEngine.kt`) **and** in `private object NoOpTestSyncStateStore` (`sync/SyncUpsertWritesEveryColumnTest.kt`).
- Any DAO added or removed must be reflected in `DatabaseModule`'s abstract accessors, `AppModule`'s `@Provides` funcs, and the test fakes in the same commit.
- Never stage `.kotlin/sessions/`.
- Deleted files are recoverable from git history; do not carry dead code forward.

---

## File Structure

**Server — created**
- `server/src/main/resources/db/migration/V6__relax_comment_nullability.sql` — `alter table transactions alter column comment drop not null;` so a payload that omits `comment` binds NULL instead of failing. Task 1 owns this; `V1__initial_schema.sql` declares `comment text not null`.
- `server/src/main/resources/db/migration/V7__family_departed_at.sql` — adds `members.departed_at timestamptz` + `create index index_members_departed_at on members (departed_at)`.
- `server/src/main/resources/db/migration/V8__drop_family_governance.sql` — drops `family_state`, `period_limits`, `spend_assignments`.

**Server — modified**
- `server/src/main/kotlin/family/sync/SyncStore.kt` — 9 `TableSpec`s → 1; delete `FAMILY_GOVERNED_TABLES`; `authorize()` → two rules; `storedFacts()` collapse; `write()` stamps `family_id`/`member_id` from the token and never rebinds an existing row's member; `pull()`/`readWindow()` accept `since`.
- `server/src/main/kotlin/family/sync/PayloadValidation.kt` — delete `BUCKETS`, `ASSIGNMENT_STATUSES`.
- `server/src/main/kotlin/family/sync/SyncRoutes.kt` — read and forward optional `since`.
- `server/src/main/kotlin/family/sync/SyncContractExport.kt` — `SCHEMA_VERSION` 2 → 3; drop `DEFAULT_MAX_OUTSTANDING_INVITES` from `limits()`.
- `server/src/main/kotlin/family/sync/PushMerge.kt` — delete `RejectReason.OWNER_ONLY` (no longer producible).
- `server/src/main/kotlin/family/family/FamilyStore.kt` — `createFamily` also mints a join code; `leave` retains the member row and stamps `departed_at`; delete `mintInvite`, `isOwner`, `maxOutstandingInvites`; `FamilyCredentials` gains `joinCode`; `FamilyMember.isOwner` → `departed`.
- `server/src/main/kotlin/family/family/FamilyRoutes.kt` — delete `post("/invite")` and the redundant `tokenService.revoke(token)` inside `post("/leave")`; emit `departed`.

**Server — tests modified/deleted**
- `SyncRouteTest.kt` — drop every dropped-table/governance test; add `since` + cross-member tests.
- `SyncContractExportTest.kt`, `InviteRedeemTest.kt`, `FamilyBodyTest.kt`, `EmbeddedPostgres.kt` — update for the new surface.
- Delete: `SchemaMigrationTest` needs no change.

**Client — created**
- `app/src/main/java/com/danilkinkin/buckwheat/data/entities/FamilyTransaction.kt`
- `app/src/main/java/com/danilkinkin/buckwheat/data/dao/FamilyTransactionDao.kt`
- `app/src/main/java/com/danilkinkin/buckwheat/family/FamilySheet.kt`
- `app/src/main/java/com/danilkinkin/buckwheat/family/FamilyViewModel.kt`
- `app/src/main/java/com/danilkinkin/buckwheat/family/MemberDetailSheet.kt`
- `app/src/test/java/com/danilkinkin/buckwheat/data/Migration21To22Test.kt`
- `app/src/test/java/com/danilkinkin/buckwheat/family/FamilyViewModelTest.kt`
- `app/src/test/java/com/danilkinkin/buckwheat/family/MemberDetailSheetTest.kt`

**Client — deleted** (Task 3 removes the first group, Task 4 the second)
`app/src/main/java/com/danilkinkin/buckwheat/family/{FamilyBudgetMath,FamilyBudgetSheet,FamilyBudgetViewModel,FamilyInsightService,FamilySnapshot,MemberTagEngine,SpendAssignmentLogic,SpendAssignmentsViewModel}.kt`, `settings/{FamilyMembersSection,FamilySyncSheet}.kt`, and tests `family/{FamilyBudgetMathTest,FamilyBudgetSheetRenderTest,FamilySnapshotPrivacyTest,MemberTagEngineTest}.kt`; then `data/entities/{FamilyState,PeriodLimit,SpendAssignment}.kt` and `data/dao/{FamilyStateDao,PeriodLimitDao,SpendAssignmentDao}.kt`.

**Client — modified**
`di/DatabaseModule.kt`, `di/AppModule.kt`, `di/SyncModule.kt`, `sync/{SyncTables,SyncModels,SyncPayloads,SyncBindings,RoomSyncDatabase,SyncStateStore,SyncEngine,HttpSyncClient,HttpFamilyApi,FamilyMembersCache,FamilySessionStore,FamilySyncRegistrar,FamilySyncCoordinator,SyncDirtyMarker}.kt`, `settings/{Settings,FamilySyncViewModel}.kt`, `home/BottomSheets.kt`, `history/History.kt`, `res/values/strings.xml`.

---

## Task 0: Build harness helper

**Files:**
- Create: `tools/gradle-detached.cmd` (Windows-only helper, committed so every task uses the same protocol)

**Interfaces:**
- Consumes: nothing.
- Produces: `tools\gradle-detached.cmd <gradle-args…>` — launches `gradlew.bat <args>` in a minimized detached console rooted at the repo root, and prints two lines: `LOG=<absolute path to C:\Users\suraj\AppData\Local\Temp\opencode\gradle-<guid>.log>` and `PID=<process id of this build>`. The log ends with a final `EXIT=<gradle exit code>` line once the build finishes; no `EXIT=` line yet means it is still running. A `<log>.pid` sidecar carries the same PID. Every later task invokes builds through it. Do not pass arguments containing `&`, `|`, `<`, `>`, or `!` — the launch path cannot forward them.

- [x] **Step 1: Create the script**

Implemented in `tools/gradle-detached.cmd`. Requirements it satisfies, in place of a literal script body:

- Log path is `C:\Users\suraj\AppData\Local\Temp\opencode\gradle-<guid>.log`, the GUID generated per launch so two concurrent builds cannot collide and truncate each other's log. Falls back to `%TEMP%\opencode` only if that mandated parent path does not exist on the machine.
- Launches via `System.Diagnostics.Process::Start` with `UseShellExecute` and a minimized window, working directory `%~dp0..` so a bare `gradlew.bat` resolves regardless of the caller's CWD.
- Prints `LOG=<path>` and `PID=<n>`, and writes the same PID to `<log>.pid`.
- The launched shell is `cmd /v:on /c`, so the trailing `echo EXIT=!errorlevel!` captures the Gradle command's own exit code, not PowerShell's or the wrapper's, and writes it to the log on both success and failure paths.
- An uncreatable log directory, or a child that fails to start, prints `ERROR:` to stderr and exits non-zero rather than reporting a `LOG=` that will never exist.
- Exits 0 immediately; the log may not exist yet when `LOG=` prints.

- [ ] **Step 2: Verify the wrapper resolves**

Run (detached): `tools\gradle-detached.cmd --version`
Expected: log shows `Gradle 8.14.3` followed by a final `EXIT=0` line.

---

## Task 1: Server — one synced table, token-stamped writes, `since` on the pull

**Files:**
- Create: `server/src/main/resources/db/migration/V6__relax_comment_nullability.sql` — `alter table transactions alter column comment drop not null;`. `V1__initial_schema.sql` declares `comment text not null`, but the single `TableSpec` treats `comment` as an optional wire field, so a commentless payload would bind NULL and 500 without this.
- Modify: `server/src/main/kotlin/family/sync/SyncStore.kt`
- Modify: `server/src/main/kotlin/family/sync/PayloadValidation.kt`
- Modify: `server/src/main/kotlin/family/sync/PushMerge.kt`
- Modify: `server/src/main/kotlin/family/sync/SyncRoutes.kt`
- Modify: `server/src/main/kotlin/family/sync/SyncContractExport.kt`
- Test: `server/src/test/kotlin/family/sync/SyncRouteTest.kt`, `SyncContractExportTest.kt`

**Interfaces:**
- Consumes: nothing (server compiles standalone).
- Produces:
  - `SyncStore.sync(familyId: String, memberId: String, cursor: Long, changes: List<PushChange>, since: Long? = null): SyncOutcome`
  - `object SyncTables { val ALL: List<TableSpec> }` with exactly one element, `transactions`, columns `type/value/spentAt/comment/category`, `hasMember = true`.
  - `enum class RejectReason` without `OWNER_ONLY`.
  - `SyncContractExport.SCHEMA_VERSION == 3`.

- [ ] **Step 1: Write the failing cross-member test**

Add to `SyncRouteTest.kt`:

```kotlin
@Test
fun aTokenStampsFamilyAndMemberEvenWhenThePayloadForgesThem() = runServer {
    val family = newFamily()
    val id = UUID.randomUUID().toString()
    val payload = """{"type":"SPENT","value":"9.99","spentAt":1700000000000,"comment":"forged","category":null,"familyId":"${family.familyId}","memberId":"${family.ownerMemberId}"}"""
    val response = postJson("/v1/sync", syncBody(0L, change("transactions", id, 1, 1L, payload)), family.guestToken)
    assertEquals(200, response.status.value)
    assertEquals(setOf("transactions:$id"), response.json().acceptedKeys().toSet())
    TestDatabase.dataSource.connection.use { connection ->
        connection.prepareStatement("select family_id::text, member_id::text from transactions where id = ?::uuid").use { statement ->
            statement.setUuid(1, UUID.fromString(id))
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                assertEquals(family.familyId, rows.getString(1))
                assertEquals(family.guestMemberId, rows.getString(2))
            }
        }
    }
}

@Test
fun updatingAnotherMembersRowIsRefused() = runServer {
    val family = newFamily()
    val id = UUID.randomUUID().toString()
    postJson("/v1/sync", syncBody(0L, change("transactions", id, 1, 1L, spentPayload())), family.ownerToken)
    val response = postJson("/v1/sync", syncBody(0L, change("transactions", id, 2, 2L, spentPayload("99.00"))), family.guestToken)
    assertEquals("cross_member_write", response.json().conflicts().single().text("reason"))
}

@Test
fun deletingAnotherMembersRowIsRefused() = runServer {
    val family = newFamily()
    val id = UUID.randomUUID().toString()
    val payload = """{"type":"SPENT","value":"9.99","spentAt":1700000000000,"comment":"theirs","category":null}"""
    postJson("/v1/sync", syncBody(0L, change("transactions", id, 1, 1L, payload)), family.ownerToken)
    val response = postJson("/v1/sync", syncBody(0L, change("transactions", id, 2, 2L, tombstonePayload(), deletedAt = 2L)), family.guestToken)
    assertEquals("cross_member_write", response.json().conflicts().single().text("reason"))
    assertEquals(1, countRows("transactions", family.familyId))
}

@Test
fun theAuthorCanUpdateAndDeleteTheirOwnRow() = runServer {
    val family = newFamily()
    val id = UUID.randomUUID().toString()
    val payload = spentPayload()
    assertEquals(HttpStatusCode.OK, postJson("/v1/sync", syncBody(0L, change("transactions", id, 1, 1L, payload)), family.ownerToken).status)
    val update = """{"type":"SPENT","value":"13.00","spentAt":1700000000000,"comment":"mine","category":null}"""
    assertEquals(HttpStatusCode.OK, postJson("/v1/sync", syncBody(0L, change("transactions", id, 2, 2L, update)), family.ownerToken).status)
    val delete = postJson("/v1/sync", syncBody(0L, change("transactions", id, 3, 3L, tombstonePayload(), deletedAt = 3L)), family.ownerToken)
    assertEquals(setOf("transactions:$id"), delete.json().acceptedKeys().toSet())
}

@Test
fun sinceBoundsThePull() = runServer {
    val family = newFamily()
    val old = UUID.randomUUID().toString()
    val recent = UUID.randomUUID().toString()
    postJson("/v1/sync", syncBody(0L, change("transactions", old, 1, 1_000L, spentPayload("1.00"))), family.ownerToken)
    postJson("/v1/sync", syncBody(0L, change("transactions", recent, 1, 9_000_000_000L, spentPayload("2.00"))), family.ownerToken)
    val response = postJson("/v1/sync", """{"cursor":0,"since":5000000000,"changes":[]}""", family.guestToken)
    assertEquals(listOf("transactions:$recent"), response.json().recordKeys())
}

@Test
fun theOnlySyncedTableIsTransactions() = runServer {
    val family = newFamily()
    val id = UUID.randomUUID().toString()
    val response = postJson("/v1/sync", syncBody(0L, change("savings_goals", id, 1, 1L, goalPayload())), family.ownerToken)
    assertEquals(400, response.status.value)
    assertEquals("unknown_table", response.json().text("code"))
}
```

Also change the test helper `newFamily()` so it does not mint an invite. Replace its body with a direct two-member setup:

```kotlin
private suspend fun ApplicationTestBuilder.newFamily(ownerName: String = "Owner"): Family {
    val credentials = postJson("/v1/family/create", """{"displayName":"$ownerName"}""").json()
    val familyId = credentials.text("familyId") ?: error("no familyId")
    val ownerMemberId = credentials.text("memberId") ?: error("no memberId")
    val ownerToken = credentials.text("token") ?: error("no token")
    val guestMemberId = TestDatabase.addMember(familyId, "Guest")
    val guestToken = TestDatabase.issueToken(familyId, guestMemberId)
    return Family(familyId, ownerMemberId, ownerToken, guestMemberId, guestToken)
}
```

`TestDatabase.issueToken` does not exist yet — add it to `EmbeddedPostgres.kt`:

```kotlin
fun issueToken(familyId: String, memberId: String): String =
    family.sync.auth.TokenService(dataSource).mint(memberId, familyId)
```

- [ ] **Step 2: Run the tests and watch them fail**

Run (detached, from repo root): `tools\gradle-detached.cmd -p server test --tests "family.sync.SyncRouteTest"`
Expected: FAIL — `cross_member_write` conflicts absent, `since` ignored, `savings_goals` still accepted, `issueToken` unresolved.

- [ ] **Step 3: Collapse `SyncTables.ALL` to one spec**

In `SyncStore.kt`, replace the whole `object SyncTables` body:

```kotlin
object SyncTables {
    val ALL: List<TableSpec> = listOf(
        TableSpec(
            name = "transactions",
            hasMember = true,
            columns = listOf(
                PayloadColumn("type", "type", SqlType.TEXT, false, TRANSACTION_TYPES),
                PayloadColumn("value", "value", SqlType.NUMERIC, false),
                PayloadColumn("spentAt", "spent_at", SqlType.BIGINT, false),
                PayloadColumn("comment", "comment", SqlType.TEXT, true),
                PayloadColumn("category", "category", SqlType.TEXT, true),
            ),
        ),
    )

    fun require(name: String): TableSpec = ALL.firstOrNull { it.name == name }
        ?: throw BadRequestException("unknown_table")
}
```

Delete the `internal val FAMILY_GOVERNED_TABLES` line.

- [ ] **Step 4: Delete `BUCKETS` and `ASSIGNMENT_STATUSES`**

In `PayloadValidation.kt` delete both `val` declarations. Nothing else references them once Step 3 lands.

- [ ] **Step 5: Replace `authorize()` with the two rules**

In `SyncStore.kt` delete `isOwnerFor`, `PreparedChange.incoming`, `sameAmount`, and `StoredFacts`, and replace `authorize` plus `storedFacts` with:

```kotlin
private fun storedMemberId(connection: Connection, familyId: String, id: String): String? =
    connection.prepareStatement("select member_id::text from transactions where id = ?::uuid and family_id = ?::uuid").use { statement ->
        statement.setUuid(1, UUID.fromString(id))
        statement.setUuid(2, UUID.fromString(familyId))
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

private fun authorize(connection: Connection, familyId: String, entry: PreparedChange, memberId: String): Authorization {
    val change = entry.change
    val existing = storedMemberId(connection, familyId, change.id)
    if (change.deletedAt != null && existing == null) return Authorization.Allowed
    return if (existing != null && existing != memberId) {
        Authorization.Denied(RejectReason.CROSS_MEMBER_WRITE, existing)
    } else {
        Authorization.Allowed
    }
}
```

`member_id` is deliberately absent from the `transactions` `TableSpec`, so `readPayload` never returns it and an incoming row's member is always the token's member — that is the "absent" half of the rule. The `existing != memberId` comparison is the second half. Leave the `values` preparation in `sync()` unchanged (`values = if (change.deletedAt == null) readPayload(change.payload, spec) else null`).

- [ ] **Step 6: Make `write()` stamp the token's member**

In `write()` replace the `on conflict … do update set member_id = excluded.member_id` clause so the update list no longer touches `member_id`:

```kotlin
on conflict (id) do update set
    type = excluded.type,
    value = excluded.value,
    spent_at = excluded.spent_at,
    comment = excluded.comment,
    category = excluded.category,
    seq = nextval('sync_sequence'),
    updated_at = excluded.updated_at,
    version = excluded.version,
    deleted_at = excluded.deleted_at
where transactions.family_id = excluded.family_id
```

- [ ] **Step 7: Thread `since` through the pull**

Change the signatures and add the filter:

```kotlin
fun sync(familyId: String, memberId: String, cursor: Long, changes: List<PushChange>, since: Long? = null): SyncOutcome
private fun pull(connection: Connection, familyId: String, cursor: Long, since: Long?): PullPage
private fun readWindow(connection: Connection, familyId: String, cursor: Long, since: Long?): List<WindowRow>
```

Inside `readWindow`, the per-table SELECT becomes:

```sql
select '<name>' as table_name, id, seq from <name>
where family_id = ?::uuid and seq > ? and (?::bigint is null or updated_at >= ?::bigint)
```

with the two extra values bound as `setNull(Types.BIGINT)` / `setLong` when `since == null`. Keep the `union all`, `order by seq, table_name`, and `limit ?` (`MAX_PULL_ROWS + 1`) unchanged.

- [ ] **Step 8: Accept `since` in the route**

In `SyncRoutes.kt`, change the `store.sync(...)` call to pass `since = body.optionalLong("since")`. `optionalLong` already exists and returns `null` for a missing key.

- [ ] **Step 9: Bump the contract and drop the invite limit**

In `SyncContractExport.kt` set `const val SCHEMA_VERSION = 3`, and in `limits()` remove the `DEFAULT_MAX_OUTSTANDING_INVITES` entry so only `MAX_POOL_SIZE` remains. In `PushMerge.kt` delete `OWNER_ONLY("owner_only")` from `RejectReason`.

- [ ] **Step 10: Update the contract test**

In `SyncContractExportTest.kt` replace `theContractCarriesTheHouseholdColumns` with:

```kotlin
@Test
fun theContractCarriesExactlyOneTable() {
    val tables = contract().jsonObject["tables"]!!.jsonArray
    assertEquals(1, tables.size)
    val columns = tables.single().jsonObject["columns"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
    assertEquals(listOf("type", "value", "spentAt", "comment", "category"), columns)
}

@Test
fun theContractIsAtSchemaVersionThree() {
    assertEquals(3, contract().jsonObject["schemaVersion"]!!.jsonPrimitive.int)
}
```

Deleting `OWNER_ONLY` is fine for `theContractCarriesTheLimitsAndRejectionReasonsTheServerUses` — it compares against `RejectReason.entries`, not a literal list.

- [ ] **Step 11: Delete the now-impossible tests in `SyncRouteTest.kt`**

Remove every test whose body references `archivedPayload`, `periodPayload`, `goalPayload`, `poolPayload`, `limitPayload`, `householdPayload`, `requestPayload`, `answeredRequestPayload`, or `cross_family_write` on a governance table, plus their helpers and the `REJECT_REASONS` entry for `owner_only`. Also delete the second `postJson("/v1/family/invite", …)` call at line 1689 and replace it with `TestDatabase.addMember(family.familyId, "Guest")`.

- [ ] **Step 12: Run the server suite**

Run (detached): `tools\gradle-detached.cmd -p server test`
Expected: BUILD SUCCESSFUL, all `family.sync.*` tests green. `SchemaMigrationTest` passes untouched.

- [ ] **Step 13: Commit**

```bash
git add server/src/main/kotlin/family/sync server/src/test/kotlin/family/sync/SyncRouteTest.kt server/src/test/kotlin/family/sync/SyncContractExportTest.kt server/src/test/kotlin/family/sync/EmbeddedPostgres.kt
git commit -m "feat(server): sync only transactions, stamp member from token, add since"
```

---

## Task 2: Server — departed members, join code on create, no invite route

**Files:**
- Create: `server/src/main/resources/db/migration/V7__family_departed_at.sql`
- Create: `server/src/main/resources/db/migration/V8__drop_family_governance.sql`
- Modify: `server/src/main/kotlin/family/family/FamilyStore.kt`
- Modify: `server/src/main/kotlin/family/family/FamilyRoutes.kt`
- Test: `server/src/test/kotlin/family/sync/InviteRedeemTest.kt`, `FamilyBodyTest.kt`

**Interfaces:**
- Consumes: Task 1's `SyncStore.sync(..., since)` (unchanged by this task).
- Produces:
  - `FamilyCredentials(familyId: String, memberId: String, token: String, joinCode: String)`
  - `FamilyMember(id: String, displayName: String, departed: Boolean, joinedAt: String)`
  - `FamilyStore.createFamily(displayName: String): FamilyCredentials` — returns a join code.
  - `FamilyStore.leave(principal: Principal)` — deletes tokens, keeps the member row, stamps `departed_at = now()`.
  - `FamilyStore.familyMembers(familyId: String): List<FamilyMember>`
  - Routes remaining under `/v1/family`: `post("/create")`, `post("/join")`, `post("/leave")`, `post("/whoami")`, `post("/members")`, `get("/members")`.

- [ ] **Step 1: Write the failing tests**

In `InviteRedeemTest.kt` add:

```kotlin
@Test
fun creatingAFamilyReturnsAJoinCodeThatAnotherDeviceCanRedeem() {
    runServer {
        val credentials = postJson("/v1/family/create", """{"displayName":"Owner"}""").json()
        val code = credentials.text("joinCode") ?: error("no joinCode")
        val guest = postJson("/v1/family/join", """{"code":"$code","displayName":"Guest"}""").json()
        assertNotNull(guest.text("token"))
    }
}

@Test
fun leavingKeepsTheMemberRowAndMarksItDeparted() {
    runServer {
        val credentials = postJson("/v1/family/create", """{"displayName":"Owner"}""").json()
        val token = credentials.text("token")!!
        val joinCode = credentials.text("joinCode")!!
        val guest = postJson("/v1/family/join", """{"code":"$joinCode","displayName":"Guest"}""").json()
        val guestId = guest.text("memberId")!!
        val guestToken = guest.text("token")!!

        assertEquals(HttpStatusCode.OK, postJson("/v1/family/leave", "{}", guestToken).status)

        assertFalse(TestDatabase.columnIsNull("members", guestId, "departed_at"))
        assertEquals(0, TestDatabase.countTokens(guestId))
        assertEquals(2, TestDatabase.countRows("members"))
        val members = postJson("/v1/family/members", "{}", token).json()
        val ids = members["members"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(ids.contains(guestId))
    }
}

@Test
fun theMemberListCarriesADepartedFlag() {
    runServer {
        val credentials = postJson("/v1/family/create", """{"displayName":"Owner"}""").json()
        val token = credentials.text("token")!!
        val members = postJson("/v1/family/members", "{}", token).json()
        assertEquals("false", members.jsonObject["members"]!!.jsonArray.single().jsonObject["departed"]!!.jsonPrimitive.content)
    }
}

@Test
fun thereIsNoInviteRoute() {
    runServer {
        val token = postJson("/v1/family/create", """{"displayName":"Owner"}""").json().text("token")!!
        assertEquals(HttpStatusCode.NotFound, postJson("/v1/family/invite", "{}", token).status)
    }
}
```

Add the two `EmbeddedPostgres.kt` helpers:

```kotlin
fun columnIsNull(table: String, id: String, column: String): Boolean =
    dataSource.connection.use { connection ->
        connection.prepareStatement("select $column from $table where id = ?::uuid").use { statement ->
            statement.setUuid(1, UUID.fromString(id))
            statement.executeQuery().use { rows -> rows.next() && rows.getObject(1) == null }
        }
    }

fun countTokens(memberId: String): Int =
    dataSource.connection.use { connection ->
        connection.prepareStatement("select count(*) from member_tokens where member_id = ?::uuid").use { statement ->
            statement.setUuid(1, UUID.fromString(memberId))
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }
```

- [ ] **Step 2: Run the tests and watch them fail**

Run (detached): `tools\gradle-detached.cmd -p server test --tests "family.sync.InviteRedeemTest"`
Expected: FAIL — `create` has no `joinCode`, `leave` removes the member row, `/invite` still exists.

- [ ] **Step 3: Write the migrations**

`server/src/main/resources/db/migration/V7__family_departed_at.sql`:

```sql
alter table members add column if not exists departed_at timestamptz;
create index if not exists index_members_departed_at on members (departed_at);
```

`server/src/main/resources/db/migration/V8__drop_family_governance.sql`:

```sql
drop table if exists spend_assignments;
drop table if exists period_limits;
drop table if exists family_state;
```

Keep row-level security untouched: `members` already has RLS enabled per `SchemaMigrationTest`.

- [ ] **Step 4: Update `FamilyStore`**

Delete `mintInvite`, `isOwner`, `claimInvite`, `unusableInvite`, `pruneInvites`, `countOutstandingInvites`, `readInvite`, `data class Invite`, the `REDEEMED_INVITE_RETENTION_DAYS` / `EXPIRED_INVITE_RETENTION_DAYS` / `INVITE_LIFETIME` / `DEFAULT_MAX_OUTSTANDING_INVITES` constants, and the `maxOutstandingInvites` constructor parameter. Change the two data classes:

```kotlin
data class FamilyCredentials(familyId: String, memberId: String, token: String, joinCode: String)
data class FamilyMember(id: String, displayName: String, departed: Boolean, joinedAt: String)
```

`createFamily` becomes:

```kotlin
fun createFamily(displayName: String): FamilyCredentials = dataSource.connection.use { connection ->
    connection.autoCommit = false
    try {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) throw BadRequestException("display_name_required")
        val familyId = insertReturningUuid(connection, "insert into families default values returning id")
        val memberId = insertReturningUuid(
            connection,
            "insert into members (family_id, display_name, is_owner) values (?::uuid, ?, true) returning id",
            familyId, trimmed,
        )
        val code = generateCode()
        connection.prepareStatement("insert into invites (code, family_id, created_by, expires_at) values (?::uuid, ?::uuid, ?::uuid, now() + interval '30 days')").use { statement ->
            statement.setUuid(1, UUID.fromString(code))
            statement.setUuid(2, UUID.fromString(familyId))
            statement.setUuid(3, UUID.fromString(memberId))
            statement.executeUpdate()
        }
        val token = tokenService.mint(connection, memberId, familyId)
        connection.commit()
        FamilyCredentials(familyId, memberId, token, code)
    } catch (failure: Throwable) {
        connection.rollback()
        throw failure
    } finally {
        connection.autoCommit = true
    }
}
```

`generateCode()` must use `CODE_ALPHABET` and `CODE_LENGTH` as before. `redeemInvite(code, displayName)` keeps its behaviour (claim, insert member, mint token) but returns `FamilyCredentials(familyId, memberId, token, joinCode = code)`.

`leave` becomes:

```kotlin
fun leave(principal: Principal) = dataSource.connection.use { connection ->
    connection.autoCommit = false
    try {
        connection.prepareStatement("update members set departed_at = now() where id = ?::uuid and family_id = ?::uuid").use { statement ->
            statement.setUuid(1, principal.memberId)
            statement.setUuid(2, principal.familyId)
            statement.executeUpdate()
        }
        connection.prepareStatement("delete from member_tokens where member_id = ?::uuid").use { statement ->
            statement.setUuid(1, principal.memberId)
            statement.executeUpdate()
        }
        connection.commit()
    } catch (failure: Throwable) {
        connection.rollback()
        throw failure
    } finally {
        connection.autoCommit = true
    }
}
```

`familyMembers` selects the new column and maps it:

```kotlin
fun familyMembers(familyId: String): List<FamilyMember> = dataSource.connection.use { connection ->
    connection.prepareStatement("select id::text, display_name, departed_at is not null as departed, joined_at from members where family_id = ?::uuid order by joined_at, id").use { statement ->
        statement.setUuid(1, UUID.fromString(familyId))
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        FamilyMember(
                            id = rows.getString(1),
                            displayName = rows.getString(2),
                            departed = rows.getBoolean(3),
                            joinedAt = rows.getTimestamp(4).toInstant().toString(),
                        ),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Update `FamilyRoutes`**

Delete `post("/invite")`, the `inviteLimiter` local, the `MAX_INVITE_CODE_LENGTH` constant if unused, and the `tokenService.revoke(token)` call inside `post("/leave")` (the `leave` store method already deletes the token rows). Change `FamilyCredentials.response()` to include the code:

```kotlin
private fun FamilyCredentials.response(): Map<String, String> = mapOf(
    "familyId" to familyId,
    "memberId" to memberId,
    "token" to token,
    "joinCode" to joinCode,
)
```

In `respondWithMembers`, replace `put("isOwner", …)` with `put("departed", it.departed)`.

- [ ] **Step 6: Update the remaining server tests**

In `FamilyBodyTest.kt` replace the `mintInvite(token)` helper with a join-code read:

```kotlin
private fun ApplicationTestBuilder.createFamilyWithCode(displayName: String = "Owner"): JsonObject =
    postJson("/v1/family/create", """{"displayName":"$displayName"}""").json()
```

and rewrite every test that asserted invite behaviour into a body-validation test against `join`.

In `InviteRedeemTest.kt` delete `mintingInvitesIsRateLimitedPerCaller`, `oneOwnerCannotFloodTheInviteTable`, `mintingAnInvitePrunesInvitesNobodyCanRedeemAnymore`, `aTamperedTokenCannotMintAnInvite`, `aNonOwnerCannotMintAnInvite`, `anUnauthenticatedDeviceCannotMintAnInvite`, `theOwnerMintsAnInviteAndAnotherDeviceRedeemsIt`, `aUsedInviteNeverCreatesASecondMember`, `anExpiredInviteCreatesNoMember`, `anExpiredInviteCodeIsRefusedDistinctlyFromAUsedOne`, and `anInviteCodeIsSingleUse`; replace them with join-code equivalents already covered by Step 1. Rename `theMemberListCarriesTheIdAndOwnerFlag` to `theMemberListCarriesTheIdAndDepartedFlag`. Keep `leavingRevokesTheTokenAndRemovesTheMembership` but rename to `leavingRevokesTheTokenAndKeepsTheDepartedMembership` and invert its assertion to use `columnIsNull`.

- [ ] **Step 7: Run the full server suite**

Run (detached): `tools\gradle-detached.cmd -p server test`
Expected: BUILD SUCCESSFUL. `SchemaMigrationTest` still green (V6/V7 do not break its hardcoded table-name sets until V7 is applied by Flyway — if `everyFamilyTableHasRowLevelSecurityEnabled` or `tableNames()` fails after V7 lands, widen that assertion to the post-V7 set: `archived_transactions, budget_periods, families, family_settings, invites, member_tokens, members, recurring_templates, saved_categories, saved_tags, savings_goals, transactions`).

- [ ] **Step 8: Commit**

```bash
git add server/src/main/resources/db/migration server/src/main/kotlin/family/family server/src/test/kotlin/family/sync
git commit -m "feat(server): departed members, join code on create, drop invite route"
```

---

## Task 3: Client — delete the family UI layer before the schema changes

Room 22 forces `FamilyState`/`PeriodLimit`/`SpendAssignment` out of the entity list, and those types are consumed by the family ViewModels. Deleting the family UI layer first keeps every commit buildable, so every later task can run its test cycle.

**Files:**
- Delete: `app/src/main/java/com/danilkinkin/buckwheat/family/{FamilyBudgetMath,FamilyBudgetSheet,FamilyBudgetViewModel,FamilyInsightService,FamilySnapshot,MemberTagEngine,SpendAssignmentLogic,SpendAssignmentsViewModel}.kt`
- Delete: `app/src/main/java/com/danilkinkin/buckwheat/settings/{FamilySyncSheet,FamilyMembersSection}.kt`
- Delete: `app/src/test/java/com/danilkinkin/buckwheat/family/{FamilyBudgetMathTest,FamilyBudgetSheetRenderTest,FamilySnapshotPrivacyTest,MemberTagEngineTest}.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/home/BottomSheets.kt` — remove the `FAMILY_BUDGET_SHEET` and `FAMILY_SYNC_SHEET` `BottomSheetWrapper` blocks, their imports, and any now-unused `hiltViewModel<FamilyBudgetViewModel>()` wiring
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/settings/Settings.kt` — remove both family rows (lines 215–245), the `com.danilkinkin.buckwheat.family.FAMILY_BUDGET_SHEET` import, and `FamilySyncSheet`/`SyncStatusChip`-adjacent imports that become unused

**Interfaces:**
- Consumes: nothing.
- Produces: no family sheet is reachable from Settings until Task 7. `FamilySyncViewModel`, `SyncStatusChip`, `SyncConflictsSheet`, `family/` package (now empty), and the `transactions`/`archived_transactions` entities all stay untouched.

- [ ] **Step 1: Record the compile-clean baseline**

Run (detached): `tools\gradle-detached.cmd compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails, stop — the baseline was already broken and this task is not the cause.

- [ ] **Step 2: Delete the files and their registrations**

Delete the ten production files and four test files listed above. Then in `BottomSheets.kt` remove the two sheet blocks and their imports. In `Settings.kt` remove both family rows and the `FAMILY_BUDGET_SHEET` import.

- [ ] **Step 3: Confirm nothing still references them**

Run: `git --no-pager grep -n "FamilyBudget\|FamilyInsightService\|FamilySnapshot\|MemberTagEngine\|SpendAssignment\|FamilySyncSheet\|FamilyMembersSection\|FAMILY_BUDGET_SHEET\|FAMILY_SYNC_SHEET" -- app/src`
Expected: no hits. Any hit is a reference this task missed; delete or rewrite it before continuing.

- [ ] **Step 4: Prove the tree still compiles**

Run (detached): `tools\gradle-detached.cmd compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the client suite to prove nothing else broke**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest`
Expected: the only failures are the six known-red tests named in Global Constraints. Confirm from `app/build/test-results/testDebugUnitTest/*.xml`.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(family): drop the budget and assignment UI layers"
```

---

## Task 4: Client — Room 22 with `family_transactions`

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/data/entities/FamilyTransaction.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/data/dao/FamilyTransactionDao.kt`
- Create: `app/src/test/java/com/danilkinkin/buckwheat/data/Migration21To22Test.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/DatabaseModule.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/AppModule.kt`
- Delete: `data/entities/{FamilyState,PeriodLimit,SpendAssignment}.kt`, `data/dao/{FamilyStateDao,PeriodLimitDao,SpendAssignmentDao}.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `FamilyTransaction` entity, `@Entity(tableName = "family_transactions", indices = [Index("member_id"), Index("date"), Index("type")])` with `id/type/value/date/comment/category/memberId/syncSeq/updatedAt/deletedAt/version`.
  - `FamilyTransactionDao` with `getAllInPeriod(startDate: Date, endDate: Date): List<FamilyTransaction>`, `getAllNow(): List<FamilyTransaction>`, `getById(id: String): FamilyTransaction?`, `upsertOne(...)`, `insert(vararg)`, `deleteById(id: String): Int`, `deleteAll()`, `updateMemberId(id: String, memberId: String)`, `deleteRowsWhereMemberDiffersFrom(memberId: String): Int`, `attributeNullMembersTo(memberId: String): Int`.
  - `val Migration21to22: Migration` and `DatabaseModule.familyTransactionDao(): FamilyTransactionDao`.
  - `AppModule.provideFamilyTransactionDao(db: DatabaseModule)`.

- [ ] **Step 1: Write the failing migration test**

Create `app/src/test/java/com/danilkinkin/buckwheat/data/Migration21To22Test.kt`:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration21To22Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DatabaseModule::class.java,
    )

    @Test
    fun theFamilyTableIsCreatedWithItsIndices() {
        helper.createDatabase(TEST_DB, 21).apply {
            Migration21to22.migrate(this)
            query("SELECT * FROM `family_transactions`").use { it.moveToFirst() }
            val indices = query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'family_transactions'")
                .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            assertTrue(indices.contains("index_family_transactions_member_id"))
            assertTrue(indices.contains("index_family_transactions_date"))
            assertTrue(indices.contains("index_family_transactions_type"))
        }.close()
    }

    @Test
    fun theThreeGovernanceTablesAreGone() {
        helper.createDatabase(TEST_DB, 21).apply {
            Migration21to22.migrate(this)
            assertTrue(runCatching { query("SELECT * FROM `family_state`") }.isFailure)
            assertTrue(runCatching { query("SELECT * FROM `period_limits`") }.isFailure)
            assertTrue(runCatching { query("SELECT * FROM `spend_assignments`") }.isFailure)
        }.close()
    }

    @Test
    fun rowsSurviveTheMigration() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL("INSERT INTO `transactions` (`id`,`type`,`value`,`date`,`comment`,`category`,`version`) VALUES ('t1','SPENT',1.5,1700000000000,'coffee',NULL,1)")
            Migration21to22.migrate(this)
            query("SELECT comment FROM `transactions` WHERE id = 't1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("coffee", it.getString(0))
            }
        }.close()
    }

    companion object {
        const val TEST_DB = "migration-21-22-test"
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.data.Migration21To22Test"`
Expected: FAIL — `Migration21to22` unresolved.

- [ ] **Step 3: Create the entity**

`app/src/main/java/com/danilkinkin/buckwheat/data/entities/FamilyTransaction.kt`:

```kotlin
package com.danilkinkin.buckwheat.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.util.Date

@Entity(
    tableName = "family_transactions",
    indices = [Index("member_id"), Index("date"), Index("type")],
)
data class FamilyTransaction(
    @PrimaryKey @ColumnInfo(name = "id") val id: String = newSyncId(),
    @ColumnInfo(name = "type") val type: TransactionType,
    @ColumnInfo(name = "value") val value: BigDecimal,
    @ColumnInfo(name = "date") val date: Date,
    @ColumnInfo(name = "comment", defaultValue = "") val comment: String = "",
    @ColumnInfo(name = "category") val category: String? = null,
    @ColumnInfo(name = "member_id") val memberId: String? = null,
    @ColumnInfo(name = "sync_seq", defaultValue = "0") val syncSeq: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "version", defaultValue = "1") val version: Int = 1,
)
```

- [ ] **Step 4: Create the DAO**

`app/src/main/java/com/danilkinkin/buckwheat/data/dao/FamilyTransactionDao.kt`:

```kotlin
package com.danilkinkin.buckwheat.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RoomTransaction
import com.danilkinkin.buckwheat.data.entities.FamilyTransaction
import com.danilkinkin.buckwheat.data.entities.TransactionType
import java.math.BigDecimal
import java.util.Date

/**
 * Every write goes through the hand-written upsert below. `@Insert(REPLACE)` and `@Upsert` are
 * both wrong here: a column left out of the SET list silently stops syncing.
 */
@Dao
interface FamilyTransactionDao {

    @Query("SELECT * FROM `family_transactions` WHERE `date` BETWEEN :startDate AND :endDate")
    fun getAllInPeriod(startDate: Date, endDate: Date): List<FamilyTransaction>

    @Query("SELECT * FROM `family_transactions`")
    fun getAllNow(): List<FamilyTransaction>

    @Query("SELECT * FROM `family_transactions` WHERE id = :id")
    fun getById(id: String): FamilyTransaction?

    @Query(
        """
        INSERT INTO `family_transactions` (
            `id`, `type`, `value`, `date`, `comment`, `category`,
            `member_id`, `sync_seq`, `updated_at`, `deleted_at`, `version`
        ) VALUES (
            :id, :type, :value, :date, :comment, :category,
            :memberId, :syncSeq, :updatedAt, :deletedAt, :version
        )
        ON CONFLICT(`id`) DO UPDATE SET
            `type` = excluded.`type`,
            `value` = excluded.`value`,
            `date` = excluded.`date`,
            `comment` = excluded.`comment`,
            `category` = excluded.`category`,
            `member_id` = excluded.`member_id`,
            `sync_seq` = excluded.`sync_seq`,
            `updated_at` = excluded.`updated_at`,
            `deleted_at` = excluded.`deleted_at`,
            `version` = excluded.`version`
        """,
    )
    suspend fun upsertOne(
        id: String,
        type: TransactionType,
        value: BigDecimal,
        date: Date,
        comment: String,
        category: String?,
        memberId: String?,
        syncSeq: Long,
        updatedAt: Long,
        deletedAt: Long?,
        version: Int,
    )

    @RoomTransaction
    suspend fun insert(vararg transaction: FamilyTransaction) {
        transaction.forEach { upsertOne(it.id, it.type, it.value, it.date, it.comment, it.category, it.memberId, it.syncSeq, it.updatedAt, it.deletedAt, it.version) }
    }

    @Query("DELETE FROM `family_transactions` WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM `family_transactions`")
    suspend fun deleteAll()

    @Query("UPDATE `family_transactions` SET `member_id` = :memberId WHERE id = :id")
    suspend fun updateMemberId(id: String, memberId: String)

    @Query("DELETE FROM `family_transactions` WHERE `member_id` IS NOT NULL AND `member_id` != :memberId")
    suspend fun deleteRowsWhereMemberDiffersFrom(memberId: String): Int

    @Query("UPDATE `family_transactions` SET `member_id` = :memberId WHERE `member_id` IS NULL")
    suspend fun attributeNullMembersTo(memberId: String): Int
}
```

- [ ] **Step 5: Write `Migration21to22`**

In `DatabaseModule.kt`, directly above `@Database`, add:

```kotlin
val Migration21to22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `family_transactions` (
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `date` INTEGER NOT NULL,
                `comment` TEXT NOT NULL DEFAULT '',
                `category` TEXT,
                `member_id` TEXT,
                `sync_seq` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                `deleted_at` INTEGER,
                `version` INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_family_transactions_member_id` ON `family_transactions` (`member_id`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_family_transactions_date` ON `family_transactions` (`date`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_family_transactions_type` ON `family_transactions` (`type`)")
        database.execSQL("DROP TABLE IF EXISTS `spend_assignments`")
        database.execSQL("DROP TABLE IF EXISTS `period_limits`")
        database.execSQL("DROP TABLE IF EXISTS `family_state`")
    }
}
```

Match the exact column types Room generated for `transactions` in schema `21.json` (open `app/schemas/com.danilkinkin.buckwheat.di.DatabaseModule/21.json` and copy `value`/`date`/`sync_seq` affinities verbatim) so `runMigrationsAndValidate` passes.

- [ ] **Step 6: Register the entity, migration, and DAO**

In `DatabaseModule.kt`: add `FamilyTransaction::class` to `entities`, change `version = 21` to `version = 22`, add `abstract fun familyTransactionDao(): FamilyTransactionDao`, delete `familyStateDao()`, `periodLimitDao()`, `spendAssignmentDao()`, and append `Migration21to22` to `MANUAL_MIGRATIONS`.

In `AppModule.kt`: delete the three `@Provides` funcs for the removed DAOs and add

```kotlin
@Provides
fun provideFamilyTransactionDao(db: DatabaseModule): FamilyTransactionDao = db.familyTransactionDao()
```

Delete `FamilyState.kt`, `PeriodLimit.kt`, `SpendAssignment.kt`, `FamilyStateDao.kt`, `PeriodLimitDao.kt`, `SpendAssignmentDao.kt`.

- [ ] **Step 7: Run the test and prove the whole tree compiles**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.data.Migration21To22Test"`
Expected: FAIL on `runMigrationsAndValidate` schema mismatch only if column affinities differ — fix the SQL to match `21.json`, then PASS. Then run (detached) `tools\gradle-detached.cmd compileDebugKotlin` and expect BUILD SUCCESSFUL: `FamilyBudgetViewModel` and `SpendAssignmentsViewModel` were the deleted entities' only remaining consumers, and Task 3 removed both files.

- [ ] **Step 8: Commit the schema and schema JSON**

```bash
git add app/schemas/com.danilkinkin.buckwheat.di.DatabaseModule/22.json app/src/main/java/com/danilkinkin/buckwheat/data app/src/main/java/com/danilkinkin/buckwheat/di app/src/test/java/com/danilkinkin/buckwheat/data/Migration21To22Test.kt
git commit -m "feat(db): room 22 with family_transactions, drop governance tables"
```

---

## Task 5: Client — sync core collapsed onto `family_transactions`

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/sync/SyncTables.kt`, `SyncModels.kt`, `SyncPayloads.kt`, `SyncBindings.kt`, `RoomSyncDatabase.kt`, `SyncStateStore.kt`, `SyncEngine.kt`, `HttpSyncClient.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/di/SyncModule.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/sync/{SyncEngineTest,RoomSyncDatabaseRoomTest,SyncUpsertWritesEveryColumnTest,SyncStateStoreTest,HttpSyncClientTest,SyncPayloadsTest,SyncPayloadContractTest}.kt`

**Interfaces:**
- Consumes: Task 4's `FamilyTransactionDao` (`getAllNow`, `getById`, `insert`, `deleteById`, `deleteAll`, `updateMemberId`, `deleteRowsWhereMemberDiffersFrom`, `attributeNullMembersTo`) and `Migration21to22`.
- Produces:
  - `SyncTables.TRANSACTIONS` only; `SyncTables.ALL = listOf(TRANSACTIONS)`; `SyncTables.APPLY_ORDER = listOf(TRANSACTIONS)`.
  - `SyncRequest(cursor: Long, changes: List<LocalRecord>, since: Long? = null)`.
  - `SyncDatabase.enrolAll(memberId: String, familyId: String, enrolledAt: Long)` now also performs the one-time re-home.
  - `SyncStateStore.isFamilyReHome22Done(): Boolean` and `SyncStateStore.markFamilyReHome22Done()`.
  - `SyncEngine(client, database, sessionProvider, syncStateStore, clock, membersCache: FamilyMembersCache? = null, familyApiFactory: FamilyApiFactory? = null, periodStart: suspend () -> Long = { 0L })`.
  - `internal fun FamilyTransaction.businessPayload(): JSONObject` and `internal fun JSONObject.readFamilyTransaction(id: String): FamilyTransaction`, `internal fun FamilyTransaction.withSyncMeta(record: LocalRecord): FamilyTransaction`.

- [ ] **Step 1: Write the failing regression test**

Add to `sync/SyncEngineTest.kt`:

```kotlin
@Test
fun aPullWritesIntoFamilyTransactionsAndNeverIntoTransactions() = runTest {
    val database = FakeSyncDatabase()
    val cache = RecordingMembersCache()
    val engine = engine(database = database, membersCache = cache, familyApiFactory = RecordingFamilyApiFactory())
    val remote = remote(table = SyncTables.TRANSACTIONS, id = "remote-1", payload = spendPayload("42.00"))
    database.responses = listOf(SyncResponse(cursor = 1L, accepted = emptyList(), records = listOf(remote), conflicts = emptyList()))

    assertEquals(SyncOutcome.Synced(1L, emptyList()), engine.sync())

    val tables = database.applied.flatMap { it.records }.map { it.table }.toSet()
    assertEquals(setOf(SyncTables.TRANSACTIONS), tables)
    assertTrue(database.upsertedTables.contains("family_transactions"))
    assertFalse(database.upsertedTables.contains("transactions"))
    assertEquals(listOf("remote-1"), cache.written.single().records.map { it.id })
}

@Test
fun thePullSendsTheCurrentPeriodStartAsSince() = runTest {
    val database = FakeSyncDatabase()
    val engine = engine(database = database, periodStart = { 1_700_000_000_000L })
    database.responses = listOf(SyncResponse(cursor = 0L, accepted = emptyList(), records = emptyList(), conflicts = emptyList()))
    engine.sync()
    assertEquals(1_700_000_000_000L, database.requests.single().since)
}
```

`FakeSyncDatabase` gains `val applied = mutableListOf<SyncApply>()`, `val upsertedTables = mutableListOf<String>()`, `val requests = mutableListOf<SyncRequest>()`, `var responses: List<SyncResponse>`, and records into them from `apply` and the injected client stub. `RecordingMembersCache` implements `FamilyMembersCache` and captures `replaceMembers`. `RecordingFamilyApiFactory` implements `FamilyApiFactory` and returns a `RecordingFamilyApi` with one member.

- [ ] **Step 2: Run it and watch it fail**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.sync.SyncEngineTest"`
Expected: FAIL — `since` field, `membersCache`/`api`/`periodStart` constructor params, and `RecordingMembersCache` do not exist.

- [ ] **Step 3: Collapse `SyncTables`**

Replace the body of `app/…/sync/SyncTables.kt`:

```kotlin
object SyncTables {
    const val TRANSACTIONS = "transactions"
    val ALL = listOf(TRANSACTIONS)
    val APPLY_ORDER = listOf(TRANSACTIONS)
}
```

- [ ] **Step 4: Add `since` to `SyncRequest` and the HTTP encoder**

In `SyncModels.kt`:

```kotlin
data class SyncRequest(val cursor: Long, val changes: List<LocalRecord>, val since: Long? = null)
```

In `HttpSyncClient.kt`, inside `encodeSyncRequest`, after writing `changes`:

```kotlin
request.since?.let { body.put("since", it) }
```

- [ ] **Step 5: Add the family-table payload to `SyncPayloads.kt`**

Delete `bucket`, `assignmentId`, and `assignedByMemberId` from `Transaction.businessPayload()` and `readTransaction()`. Add:

```kotlin
internal fun FamilyTransaction.businessPayload(): JSONObject = JSONObject().apply {
    put("type", type.name)
    put("value", value.toPlainString())
    put("spentAt", date.time)
    put("comment", comment)
    put("category", category ?: JSONObject.NULL)
}

internal fun JSONObject.readFamilyTransaction(id: String): FamilyTransaction = FamilyTransaction(
    id = id,
    type = requireString("type").readType(),
    value = requireString("value").toBigDecimalPayload(),
    date = Date(requireLong("spentAt")),
    comment = optString("comment", ""),
    category = optNullableString("category"),
    memberId = optNullableString("memberId"),
    syncSeq = optLong("syncSeq", 0L),
    updatedAt = optLong("updatedAt", 0L),
    deletedAt = optNullableLong("deletedAt"),
    version = optInt("version", 1),
)

internal fun FamilyTransaction.withSyncMeta(record: LocalRecord): FamilyTransaction = copy(
    memberId = record.memberId,
    syncSeq = record.syncSeq,
    updatedAt = record.updatedAt,
    deletedAt = record.deletedAt,
    version = record.version,
)
```

Add the missing private helper next to `optNullableString`:

```kotlin
internal fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else optLong(key)
```

- [ ] **Step 6: Point the single binding at `FamilyTransactionDao`**

In `SyncBindings.kt` change the factory to:

```kotlin
class SyncBindings(pendingMutationDao: PendingMutationDao) {
    fun gateways(familyTransactionDao: FamilyTransactionDao): List<SyncTableGateway> = listOf(
        SyncTableBinding(
            table = SyncTables.TRANSACTIONS,
            loader = { familyTransactionDao.getAllNow() },
            inserter = { familyTransactionDao.insert(it) },
            remover = { familyTransactionDao.deleteById(it) },
            idOf = { it.id },
            isDirty = { recordId -> pendingMutationDao.isPending(SyncTables.TRANSACTIONS, recordId) },
            payloadOf = { it.businessPayload().toString() },
            metaOf = { SyncMeta(it.updatedAt, it.version, it.deletedAt, it.memberId, null, it.syncSeq) },
            decoder = { record -> JSONObject(record.payload).readFamilyTransaction(record.id).withSyncMeta(record) },
        ),
    )
}
```

Keep the exact `isDirty` body the current transactions binding uses (copy it verbatim from the file so the pending-mutation lookup stays identical) and delete the other eight bindings plus the generic `binding(...)` helper.

- [ ] **Step 7: Add the re-home flag to `SyncStateStore`**

In `SyncStateStore.kt` add `val familyReHome22StoreKey = booleanPreferencesKey("familyReHome22Done")` and to the interface:

```kotlin
suspend fun isFamilyReHome22Done(): Boolean
suspend fun markFamilyReHome22Done()
```

Implement in `DataStoreSyncStateStore`:

```kotlin
override suspend fun isFamilyReHome22Done(): Boolean =
    context.syncStateDataStore.data.first()[familyReHome22StoreKey] ?: false

override suspend fun markFamilyReHome22Done() {
    context.syncStateDataStore.edit { it[familyReHome22StoreKey] = true }
}
```

Add the same two members to `internal object NoopSyncStateStore` in `SyncEngine.kt` returning `false` / no-op, and to `private object NoOpTestSyncStateStore` in `sync/SyncUpsertWritesEveryColumnTest.kt`.

- [ ] **Step 8: Re-home inside `RoomSyncDatabase.enrolAll`**

In `RoomSyncDatabase.kt`, at the top of `enrolAll`:

```kotlin
if (!syncStateStore.isFamilyReHome22Done()) {
    reHomeOwnedRows(memberId)
    syncStateStore.markFamilyReHome22Done()
}
```

and add the private helper:

```kotlin
private suspend fun reHomeOwnedRows(memberId: String) {
    val local = transactionDao.getAllNow()
    val orphans = local.filter { it.familyId != null && it.memberId != memberId }
    val attributed = local.filter { it.familyId != null && it.memberId == null }
    if (orphans.isNotEmpty()) {
        familyTransactionDao.insert(
            *orphans.map { row ->
                FamilyTransaction(
                    id = row.id,
                    type = row.type,
                    value = row.value,
                    date = row.date,
                    comment = row.comment,
                    category = row.category,
                    memberId = row.memberId,
                    syncSeq = row.syncSeq,
                    updatedAt = row.updatedAt,
                    deletedAt = row.deletedAt,
                    version = row.version,
                )
            }.toTypedArray(),
        )
        orphans.forEach { transactionDao.deleteById(it.id) }
    }
    attributed.forEach { familyTransactionDao.updateMemberId(it.id, memberId) }
    familyTransactionDao.attributeNullMembersTo(memberId)
}
```

`RoomSyncDatabase`'s constructor gains one new parameter:

```kotlin
class RoomSyncDatabase(
    private val gateways: List<SyncTableGateway>,
    private val pendingMutationDao: PendingMutationDao,
    private val syncStateStore: SyncStateStore,
    private val transactionDao: TransactionDao,
    private val familyTransactionDao: FamilyTransactionDao,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
)
```

`transactionDao` is used only by the re-home; `familyTransactionDao` only by the re-home's `insert` and by `reset()`. Update `reset()` to call `familyTransactionDao.deleteAll()` alongside `pendingMutationDao.deleteAll()` and `syncStateStore.clear()`.

- [ ] **Step 9: Pass `since` and refresh the roster in `SyncEngine`**

In `SyncEngine.kt` extend the constructor:

```kotlin
class SyncEngine(
    private val client: SyncClient,
    private val database: SyncDatabase,
    private val sessionProvider: suspend () -> FamilySession?,
    private val syncStateStore: SyncStateStore = NoopSyncStateStore,
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() },
    private val membersCache: FamilyMembersCache? = null,
    private val familyApiFactory: FamilyApiFactory? = null,
    private val periodStart: suspend () -> Long = { 0L },
)
```

Inside `runSync()`, compute `val since = periodStart()` before the paging loop and send it on every page:

```kotlin
val response = client.sync(session.token, SyncRequest(cursor = cursor, changes = changes, since = since))
```

After `database.apply(...)` and `markSynced`, add:

```kotlin
refreshRoster(session)
```

with

```kotlin
private suspend fun refreshRoster(session: FamilySession) {
    val cache = membersCache ?: return
    val factory = familyApiFactory ?: return
    runCatching { cache.replaceMembers(factory.create(session.baseUrl).members(session.token)) }
}
```

- [ ] **Step 10: Rewire DI**

```kotlin
@Provides
@Singleton
fun provideSyncDatabase(
    database: DatabaseModule,
    pendingMutationDao: PendingMutationDao,
    transactionDao: TransactionDao,
    familyTransactionDao: FamilyTransactionDao,
    syncStateStore: SyncStateStore,
): SyncDatabase = RoomSyncDatabase(
    gateways = SyncBindings(pendingMutationDao).gateways(familyTransactionDao),
    pendingMutationDao = pendingMutationDao,
    syncStateStore = syncStateStore,
    transactionDao = transactionDao,
    familyTransactionDao = familyTransactionDao,
    runInTransaction = { block -> database.withTransaction { block() } },
)
```

Extend `provideSyncEngine` to accept `membersCache: FamilyMembersCache` and `familyApiFactory: FamilyApiFactory` and forward them to `SyncEngine`. `FamilySyncCoordinator` already injects `FamilyApiFactory`, so no new `@Provides` is required anywhere.

- [ ] **Step 11: Fix the client tests**

- `sync/SyncUpsertWritesEveryColumnTest.kt`: drop the DAO cases for `PeriodLimit`, `FamilyState`, `SpendAssignment`; add a `family_transactions` case asserting every column is overwritten; update `NoOpTestSyncStateStore`.
- `sync/RoomSyncDatabaseRoomTest.kt`: delete the per-entity apply/settle/cursor tests for the eight dropped tables; rewrite `everySyncTableIsBoundInApplyOrder` to `assertEquals(listOf(SyncTables.TRANSACTIONS), SyncTables.APPLY_ORDER)`; add a `family_transactions` apply test.
- `sync/SyncPayloadsTest.kt`: delete the round-trip tests for `ArchivedTransaction`, `FamilyState`, `PeriodLimit`, `SpendAssignment`, `BudgetPeriod`, `SavedCategory`, `SavedTag`, `RecurringTemplate`, `SavingsGoal`; add `familyTransactionRoundTripsThroughItsPayload` and `theTransactionPayloadHasNoBucketOrAssignmentKeys`.
- `sync/SyncPayloadContractTest.kt`: rewrite both cross-checks to compare only `transactions` and assert `androidKeys("transactions") == listOf("type","value","spentAt","comment","category")` and `serverSpec().size == 1`.
- `sync/HttpSyncClientTest.kt`: add `theRequestCarriesSinceWhenPresent` asserting the raw JSON body contains `"since":1700000000000`.
- `sync/SyncStateStoreTest.kt`: add `theReHomeFlagStartsUnsetAndLatches`.
- `sync/EnrolRecordsTest.kt`, `sync/SyncMergeTest.kt`, `sync/SyncSchedulerTest.kt`, `sync/SyncWorkerTest.kt`, `sync/RoomSyncDirtyMarkerTest.kt`: unchanged unless compilation forces an import fix.

- [ ] **Step 12: Run the client sync suite**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.sync.*"`
Expected: all green except the known-red set. Confirm `app/build/test-results/testDebugUnitTest/*.xml` shows zero failures for `SyncEngineTest`, `RoomSyncDatabaseRoomTest`, `SyncUpsertWritesEveryColumnTest`, `SyncPayloadsTest`, `SyncPayloadContractTest`, `HttpSyncClientTest`, `SyncStateStoreTest`.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/sync app/src/main/java/com/danilkinkin/buckwheat/di/SyncModule.kt app/src/test/java/com/danilkinkin/buckwheat/sync
git commit -m "feat(sync): mirror remote transactions into family_transactions"
```

---

## Task 6: Client — family API surface without invites

**Files:**
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/sync/HttpFamilyApi.kt`, `FamilyMembersCache.kt`, `FamilySessionStore.kt`, `FamilySyncRegistrar.kt`, `FamilySyncCoordinator.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/settings/FamilySyncViewModel.kt`
- Delete: `app/src/main/java/com/danilkinkin/buckwheat/settings/FamilySyncSheet.kt`, `FamilyMembersSection.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/sync/{HttpFamilyApiTest,FamilySyncRegistrarTest,FamilySyncCoordinatorTest}.kt`, `app/src/test/java/com/danilkinkin/buckwheat/settings/{FamilySyncViewModelTest,FamilySyncServerUrlTest}.kt`

**Interfaces:**
- Consumes: Task 2's `FamilyCredentials(..., joinCode)` and `FamilyMember(..., departed, ...)`.
- Produces:
  - `FamilyCredentials(familyId: String, memberId: String, token: String, joinCode: String)`.
  - `FamilyMember(id: String, displayName: String, departed: Boolean, joinedAt: String)`.
  - `FamilySession(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String)`.
  - `FamilyApi` without `mintInvite`: `createFamily(displayName): FamilyCredentials`, `joinFamily(code, displayName): FamilyCredentials`, `whoami(token): WhoAmI`, `members(token): List<FamilyMember>`.
  - `FamilySyncCoordinator` without `invite()`, plus `suspend fun syncNow()`.
  - `FamilySyncViewModel` exposing `joinCode: StateFlow<String?>` and `refresh()`.

- [ ] **Step 1: Write the failing decoding tests**

In `sync/HttpFamilyApiTest.kt` replace `anInviteIsDecoded` with:

```kotlin
@Test
fun aJoinCodeIsDecodedFromTheCreateResponse() {
    val api = HttpFamilyApi("https://sync.example")
    assertEquals("ABCD2345", credentials(api.createFamily("Owner")).joinCode)
}

@Test
fun aDepartedMemberIsDecoded() {
    val api = HttpFamilyApi("https://sync.example")
    val members = api.members("token")
    assertEquals(listOf(false, true), members.map { it.departed })
}
```

and add a loopback stub serving `{"familyId":"f","memberId":"m","token":"t","joinCode":"ABCD2345"}` from `/v1/family/create` and `{"members":[{"id":"m","displayName":"Me","departed":false,"joinedAt":"…"},{"id":"o","displayName":"Them","departed":true,"joinedAt":"…"}]}` from `/v1/family/members`.

In `settings/FamilySyncViewModelTest.kt` add:

```kotlin
@Test
fun enrollingStoresTheJoinCode() = runTest {
    val viewModel = viewModel(api = FakeFamilyApi(joinCode = "ABCD2345"))
    viewModel.onServerUrlChange("https://sync.example")
    viewModel.onDisplayNameChange("Owner")
    viewModel.enrol()
    assertEquals("ABCD2345", viewModel.joinCode.value)
}
```

- [ ] **Step 2: Run and watch them fail**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.sync.HttpFamilyApiTest" --tests "com.danilkinkin.buckwheat.settings.FamilySyncViewModelTest"`
Expected: FAIL — `joinCode`/`departed` do not exist.

- [ ] **Step 3: Update `HttpFamilyApi`**

Delete `MintedInvite`, `decodeInvite`, and `mintInvite` from the interface and class. Change the data classes:

```kotlin
data class FamilyCredentials(familyId: String, memberId: String, token: String, joinCode: String)
data class FamilyMember(id: String, displayName: String, departed: Boolean, joinedAt: String)
```

In `decodeCredentials` add `joinCode = json.optString("joinCode", "")`. In `decodeMembers` change the entry filter to read `departed`:

```kotlin
val departed = entry.optBoolean("departed", false)
FamilyMember(id = id, displayName = displayName, departed = departed, joinedAt = entry.optString("joinedAt", ""))
```

- [ ] **Step 4: Update `FamilyMembersCache`**

In `encodeCachedMembers` / `decodeCachedMembers` swap `"isOwner"` for `"departed"` and use `optBoolean("departed", false)`.

- [ ] **Step 5: Add `joinCode` to `FamilySessionStore`**

```kotlin
data class FamilySession(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String)
```

Add `val syncJoinCodeStoreKey = stringPreferencesKey("syncFamilyJoinCode")` to the same `settingsDataStore`. Change the interface to `suspend fun save(baseUrl: String, token: String, familyId: String, memberId: String, joinCode: String)`. In `DataStoreFamilySessionStore.save`, store the code under that key. `session()` must keep returning null when base URL / token / family ID / member ID are blank, and return a session when the join code is blank too (a member who joined with someone else's code still needs a session). `clear()` must remove the join code.

- [ ] **Step 6: Update `FamilySyncRegistrar` and `FamilySyncCoordinator`**

In `FamilySyncRegistrar` delete `invite()`; change `persist` to call `sessionStore.save(baseUrl, credentials.token, credentials.familyId, credentials.memberId, credentials.joinCode)`, and keep its `membersCache.clear()` + roster fetch as-is. In `FamilySyncCoordinator` delete `invite()` and add:

```kotlin
suspend fun syncNow() = SyncScheduler.syncNow(context)
```

- [ ] **Step 7: Trim `FamilySyncViewModel`**

Delete `inviteCode`, `mintedInvite`, `mintedInviteExpiresAt`, `onInviteCodeChange`, `clearMintedInvite`, and `mintInvite()`. Add:

```kotlin
val joinCode: StateFlow<String?> = session
    .map { it?.joinCode }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```

Delete the `members` StateFlow and the `refreshMembers()` call from `init`'s session collector (the `SyncEngine` owns the roster now); keep `refreshMemberName()`. Add `fun syncNow() { viewModelScope.launch { coordinator.syncNow() } }`.

- [ ] **Step 8: Delete the two sheets and fix the tests that referenced them**

Delete `settings/FamilySyncSheet.kt` and `settings/FamilyMembersSection.kt`. In `settings/FamilySyncViewModelTest.kt` delete `mintingAnInviteStoresTheCode` and `mintingAnInviteWithoutASessionIsRefused`; in `settings/FamilySyncServerUrlTest.kt` delete `aMintedInviteKeepsItsExpiry`. In `sync/FamilySyncRegistrarTest.kt` delete the `mintInvite` override from `FakeFamilyApi` plus `inviteUsesTheStoredSession` and the invite half of `whoamiAndInviteAreNullWithoutASession`; rename it `whoamiIsNullWithoutASession`. In `sync/FamilySyncCoordinatorTest.kt` delete the `invite()` test.

- [ ] **Step 9: Run the affected suites**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.sync.*" --tests "com.danilkinkin.buckwheat.settings.*"`
Expected: green apart from the known-red set.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/sync app/src/main/java/com/danilkinkin/buckwheat/settings app/src/test/java/com/danilkinkin/buckwheat/sync app/src/test/java/com/danilkinkin/buckwheat/settings
git commit -m "feat(sync): join code on enrol, departed members, drop invite API"
```

---

## Task 7: UI — the Family sheet

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/family/FamilySheet.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/family/FamilyViewModel.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/family/MemberDetailSheet.kt`
- Modify: `app/src/main/java/com/danilkinkin/buckwheat/home/BottomSheets.kt`, `settings/Settings.kt`, `history/History.kt`, `res/values/strings.xml`
- Delete: `family/{FamilyBudgetMath,FamilyBudgetSheet,FamilyBudgetViewModel,FamilyInsightService,FamilySnapshot,MemberTagEngine,SpendAssignmentLogic,SpendAssignmentsViewModel}.kt` and `app/src/test/java/com/danilkinkin/buckwheat/family/{FamilyBudgetMathTest,FamilyBudgetSheetRenderTest,FamilySnapshotPrivacyTest,MemberTagEngineTest}.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/family/{FamilyViewModelTest,MemberDetailSheetTest}.kt`

**Interfaces:**
- Consumes: Tasks 4–6 — `FamilyTransactionDao`, `FamilySessionStore`, `FamilySyncCoordinator.syncNow()`, `FamilySyncViewModel`, `DayCard`, `numberFormat`, `SpendsRepository.getStartPeriodDate()/getFinishPeriodDate()`, `PathState`.
- Produces:
  - `const val FAMILY_SHEET = "family"` and `const val MEMBER_DETAIL_SHEET = "familyMemberDetail"` in `family/FamilySheet.kt`.
  - `FamilyViewModel` with `session: StateFlow<FamilySession?>`, `members: StateFlow<List<FamilyMember>>`, `rosterLoading: StateFlow<Boolean>`, `rosterFailed: StateFlow<Boolean>`, `familyTotal: StateFlow<BigDecimal>`, `ownSpend: StateFlow<BigDecimal>`, `spendByMember: StateFlow<Map<String, BigDecimal>>`, `periodRange: StateFlow<String>`, `dailyAverage: StateFlow<BigDecimal>`, `transactionCount: StateFlow<Int>`, plus `refresh()`, `leave()`, `disconnect()`.
  - `MemberDetailSheet` taking `memberId: String` and rendering in-window transactions.

- [ ] **Step 1: Write the failing ViewModel test**

Create `app/src/test/java/com/danilkinkin/buckwheat/family/FamilyViewModelTest.kt`:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FamilyViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun theFamilyTotalIsTheSumOfEveryMemberSpend() = runTest {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                spent("a", memberId = "me", value = "10.00", date = DAY),
                spent("b", memberId = "me", value = "5.00", date = DAY),
                spent("c", memberId = "them", value = "20.00", date = DAY),
            ),
        )
        val viewModel = FamilyViewModel(dao, sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals(BigDecimal("35.00"), viewModel.familyTotal.value)
        assertEquals(BigDecimal("15.00"), viewModel.ownSpend.value)
    }

    @Test
    fun onlyRowsInsideTheCurrentPeriodCount() = runTest {
        val dao = FakeFamilyTransactionDao(
            rows = listOf(
                spent("a", memberId = "me", value = "10.00", date = DAY),
                spent("b", memberId = "them", value = "99.00", date = Date(DAY.time - 86_400_000L)),
            ),
        )
        val viewModel = FamilyViewModel(dao, sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals(BigDecimal("10.00"), viewModel.familyTotal.value)
    }

    @Test
    fun namesComeFromTheRosterNotTheRawMemberId() = runTest {
        val viewModel = FamilyViewModel(FakeFamilyTransactionDao(), sessionStore = FakeSessionStore(session(MEMBER_ID)), spendsRepository = FakeSpendsRepository(DAY, DAY))
        assertEquals("Ren", viewModel.members.value.single { it.id == MEMBER_ID }.displayName)
    }
}
```

The fakes implement `FamilyTransactionDao`, `FamilySessionStore`, and the two `SpendsRepository` period getters. `session()` builds a `FamilySession` with `displayName`-bearing `FakeSessionStore.members`.

- [ ] **Step 2: Run it and watch it fail**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.family.FamilyViewModelTest"`
Expected: FAIL — `FamilyViewModel` does not exist.

- [ ] **Step 3: Write `FamilyViewModel`**

```kotlin
package com.danilkinkin.buckwheat.family

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val familyTransactionDao: FamilyTransactionDao,
    private val sessionStore: FamilySessionStore,
    private val spendsRepository: SpendsRepository,
    private val coordinator: FamilySyncCoordinator? = null,
    @ApplicationContext private val context: Context? = null,
) {
    val session: StateFlow<FamilySession?> = sessionStore.session()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val period: StateFlow<ClosedRange<Date>> = combine(
        spendsRepository.getStartPeriodDate(),
        spendsRepository.getFinishPeriodDate(),
    ) { start, finish -> start..(finish ?: Date(Long.MAX_VALUE)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Date()..Date(Long.MAX_VALUE))

    private val rows: StateFlow<List<FamilyTransaction>> =
        combine(session, period) { s, p -> s to p }
            .flatMapLatest { (s, p) ->
                if (s == null) flowOf(emptyList())
                else flowOf(familyTransactionDao.getAllInPeriod(p.start, p.endInclusive))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _rosterLoading = MutableStateFlow(false)
    val rosterLoading: StateFlow<Boolean> = _rosterLoading.asStateFlow()
    private val _rosterFailed = MutableStateFlow(false)
    val rosterFailed: StateFlow<Boolean> = _rosterFailed.asStateFlow()

    val spendByMember: StateFlow<Map<String, BigDecimal>> = rows.map { list ->
        list.filter { it.type == TransactionType.SPENT }
            .groupBy { it.memberId.orEmpty() }
            .mapValues { (_, group) -> group.fold(BigDecimal.ZERO) { acc, row -> acc + row.value } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val familyTotal: StateFlow<BigDecimal> = spendByMember
        .map { it.values.fold(BigDecimal.ZERO, BigDecimal::add) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)

    val ownSpend: StateFlow<BigDecimal> = combine(spendByMember, session) { byMember, s ->
        byMember[s?.memberId] ?: BigDecimal.ZERO
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BigDecimal.ZERO)

    val transactionCount: StateFlow<Int> = rows.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val members: StateFlow<List<FamilyMember>> = sessionStore.members()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val periodRange: StateFlow<String> = period.map { range ->
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
        "${format.format(range.start)} – ${format.format(range.endInclusive)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun refresh() {
        val target = coordinator ?: return
        viewModelScope.launch {
            _rosterLoading.value = true
            _rosterFailed.value = runCatching { target.members() }.isFailure
            _rosterLoading.value = false
        }
    }

    fun syncNow() { coordinator?.let { target -> viewModelScope.launch { target.syncNow() } } }

    fun leave() { coordinator?.let { target -> viewModelScope.launch { target.leave() } } }

    fun disconnect() { coordinator?.let { target -> viewModelScope.launch { target.signOut() } } }

    init {
        viewModelScope.launch {
            if (session.value != null && members.value.isEmpty()) syncNow()
        }
    }
}
```

`coordinator` and `context` are nullable-with-default so unit tests can build the ViewModel with three fakes while Hilt still injects all five; `refresh()`/`syncNow()`/`leave()`/`disconnect()` no-op when `coordinator` is null.

Add `suspend fun leave(): Boolean` to `FamilySyncCoordinator` (`api.leave(token)` on `FamilyApi`, then `database.reset(); SyncScheduler.cancel(context); registrar.signOut()`), and add `suspend fun leave(token: String)` to `FamilyApi` posting `/v1/family/leave`. Expose the roster through the session store: add `fun members(): Flow<List<FamilyMember>>` to `FamilySessionStore` and implement it in `DataStoreFamilySessionStore` by delegating to the injected `FamilyMembersCache`.

- [ ] **Step 4: Write `FamilySheet`**

One file, one public composable plus private sub-composables, each with an `@Preview`:

```kotlin
const val FAMILY_SHEET = "family"

@Composable
fun FamilySheet(viewModel: FamilyViewModel = hiltViewModel(), appViewModel: AppViewModel = hiltViewModel(), onClose: () -> Unit)
```

Structure: read state with `collectAsStateWithLifecycle()`; when `session == null` render `ConnectForm` (server URL `OutlinedTextField`, display name, **Create family** and **Join with code** buttons, inline error text under the offending field); otherwise render `SyncStatusChip` (from `SyncStatusViewModel`), the family total block (`numberFormat(context, familyTotal, currency, trimDecimalPlaces = true)` hero, own share beneath, `periodRange` caption), the member list sorted viewer-first then spend descending, a collapsed `Departed members` section, and a row of two buttons: **Leave family** (behind an `AlertDialog` confirm) and **Disconnect**.

Avatar colour comes from hashing the member id:

```kotlin
private fun avatarColor(memberId: String): Color {
    val hue = abs(memberId.hashCode()) % 360f
    return Color.hsv(hue, 0.45f, 0.85f)
}
```

Member row:

```kotlin
@Composable
private fun MemberRow(
    member: FamilyMember,
    spend: BigDecimal,
    isViewer: Boolean,
    currency: ExtendCurrency,
    onClick: () -> Unit,
)
```

Show the initials avatar (first letter of `displayName`, uppercase, fallback `"?"`), the name plus a `You` badge when `isViewer`, the hero spend, and a sub-line stating the daily average, the transaction count, the last-active day, and — for other members — that their budgets are private. Skeletons render while `rosterLoading`; a Retry button plus "couldn't refresh" renders when `rosterFailed` (still showing cached names); "Nothing spent this period." renders when `familyTotal == ZERO`. Tapping a member calls `appViewModel.openSheet(PathState(MEMBER_DETAIL_SHEET, mapOf("memberId" to member.id)))`.

- [ ] **Step 5: Write `MemberDetailSheet`**

```kotlin
const val MEMBER_DETAIL_SHEET = "familyMemberDetail"

@Composable
fun MemberDetailSheet(
    memberId: String,
    viewModel: FamilyViewModel = hiltViewModel(),
    onClose: () -> Unit,
)
```

Group the in-window rows by `LocalDate`, render one `DayCard(day, transactions, dayTotal, firstTransactionIndex, currency = currency, readOnly = true, memberNames = emptyMap())` per day inside a `LazyColumn`, and show "Nothing spent this period." when empty. Because `DayCard` already takes `readOnly`, no edit/delete affordances appear.

- [ ] **Step 6: Register the sheets and the Settings row**

In `home/BottomSheets.kt` replace the `FAMILY_BUDGET_SHEET` block (lines 148-151) with:

```kotlin
BottomSheetWrapper(name = FAMILY_SHEET) { state ->
    FamilySheet(onClose = { coroutineScope.launch { state.hide() } })
}
BottomSheetWrapper(name = MEMBER_DETAIL_SHEET) { state ->
    val memberId = state.args["memberId"] as? String
    if (memberId != null) {
        MemberDetailSheet(memberId = memberId, onClose = { coroutineScope.launch { state.hide() } })
    }
}
```

Delete the `FAMILY_SYNC_SHEET` block (lines 334-338) and its imports.

In `settings/Settings.kt` delete lines 215-229 (the `family_budget_title` row) and replace lines 231-245 with one always-visible row:

```kotlin
TextRow(
    icon = ic_share,
    text = stringResource(R.string.family_title),
    endContent = if (enrolled) { { SyncStatusChip(syncStatus) } } else null,
    endIcon = ic_arrow_right,
) { openSheet(PathState(FAMILY_SHEET)) }
```

Swap the line-37 import from `family.FAMILY_BUDGET_SHEET` to `family.FAMILY_SHEET`.

- [ ] **Step 7: Clean up `History.kt`**

Delete lines 74-81 (the `FamilySyncViewModel` roster lookup and the `memberNames` map) and the `FamilySyncViewModel`/`hiltViewModel` imports they require. `DayCard`'s `memberNames` parameter keeps its default `emptyMap()`.

- [ ] **Step 8: Delete the superseded family code and its tests**

Delete `family/FamilyBudgetMath.kt`, `FamilyBudgetSheet.kt`, `FamilyBudgetViewModel.kt`, `FamilyInsightService.kt`, `FamilySnapshot.kt`, `MemberTagEngine.kt`, `SpendAssignmentLogic.kt`, `SpendAssignmentsViewModel.kt`, and `app/src/test/java/com/danilkinkin/buckwheat/family/{FamilyBudgetMathTest,FamilyBudgetSheetRenderTest,FamilySnapshotPrivacyTest,MemberTagEngineTest}.kt`.

- [ ] **Step 9: Add the strings**

Append to `app/src/main/res/values/strings.xml`, next to the existing `family_sync_*` block (line 621):

```xml
<string name="family_title">Family</string>
<string name="family_not_connected_hint">Connect to a sync server to share your spending with the people you live with.</string>
<string name="family_server_url">Server URL</string>
<string name="family_display_name">Your name</string>
<string name="family_join_code">Family code</string>
<string name="family_create">Create family</string>
<string name="family_join">Join with code</string>
<string name="family_solo_hint">You\'re the only member so far. Share this code so someone can join.</string>
<string name="family_total_label">Family total</string>
<string name="family_your_share">Your share</string>
<string name="family_you_badge">You</string>
<string name="family_member_private_budget">Their budget stays private</string>
<string name="family_nothing_spent">Nothing spent this period.</string>
<string name="family_roster_failed">Couldn\'t refresh the member list.</string>
<string name="family_retry">Retry</string>
<string name="family_departed">Departed members</string>
<string name="family_leave">Leave family</string>
<string name="family_leave_confirm_title">Leave this family?</string>
<string name="family_leave_confirm_body">Your past transactions stay visible to the family, but you will need a new code to rejoin.</string>
<string name="family_disconnect">Disconnect</string>
<string name="family_last_active">Last active %1$s</string>
<string name="family_transactions_count">%1$d transactions</string>
<string name="family_daily_average">%1$s per day</string>
```

Every one of these must be referenced from a `stringResource` call in Step 4/Step 5 — remove any that end up unused.

- [ ] **Step 10: Write `MemberDetailSheetTest`**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemberDetailSheetTest {
    @Test
    fun onlyRowsInsideThePeriodAreListed() { /* assert the composable renders 1 of 2 rows via the fake DAO */ }
}
```

Use `createComposeRule()` and a `FamilyViewModel` built with a fake DAO containing one in-period and one out-of-period row; assert the rendered text contains the in-period comment and not the out-of-period one.

- [ ] **Step 11: Run the family and settings suites**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest --tests "com.danilkinkin.buckwheat.family.*" --tests "com.danilkinkin.buckwheat.settings.*"`
Expected: green apart from the known-red set.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/danilkinkin/buckwheat/family app/src/main/java/com/danilkinkin/buckwheat/home/BottomSheets.kt app/src/main/java/com/danilkinkin/buckwheat/settings/Settings.kt app/src/main/java/com/danilkinkin/buckwheat/history/History.kt app/src/main/res/values/strings.xml app/src/test/java/com/danilkinkin/buckwheat/family
git commit -m "feat(ui): family sheet with member detail and current-period totals"
```

---

## Task 8: Final verification and contract regeneration

**Files:**
- Modify: `app/src/test/java/com/danilkinkin/buckwheat/sync/SyncPayloadContractTest.kt` if the server-source scrape path moved
- Verify: no file changes expected beyond generated schema JSON

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: a green build plus a regenerated `docs/sync-contract.json`-style artifact if the repo tracks one.

- [ ] **Step 1: Regenerate the sync contract and confirm schema version 3**

Run (detached, from repo root): `tools\gradle-detached.cmd -p server generateSyncContract`
Expected: the generated JSON has `"schemaVersion": 3` and exactly one entry in `tables`.

- [ ] **Step 2: Run the whole server suite**

Run (detached): `tools\gradle-detached.cmd -p server test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the whole client suite**

Run (detached): `tools\gradle-detached.cmd testDebugUnitTest`
Expected: the only failures are the six known-red tests named in Global Constraints. Confirm by listing failures from `app/build/test-results/testDebugUnitTest/*.xml`.

- [ ] **Step 4: Build the debug APK**

Run (detached): `tools\gradle-detached.cmd assembleDebug`
Expected: BUILD SUCCESSFUL — this is the first commit-to-commit green build of the whole change.

- [ ] **Step 5: Confirm no dead references remain**

Run: `git grep -n "FAMILY_BUDGET_SHEET\|FAMILY_SYNC_SHEET\|FamilyBudget\|SpendAssignment\|PeriodLimit\|FamilyState\|memberNames" -- app/src/main`
Expected: no hits except `memberNames` as `DayCard`'s own parameter default.

- [ ] **Step 6: Commit any generated artifacts**

```bash
git add -A
git status --short
git commit -m "chore: regenerate sync contract at schema version 3"
```

Only commit if Step 1 or Step 4 produced tracked file changes.
