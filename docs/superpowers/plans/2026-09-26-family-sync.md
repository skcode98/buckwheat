# Family Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a family share one budget pool with per-member sub-budgets and full transaction history across all their devices, via a self-hosted backend on Render with data in the owner's existing Supabase Postgres.

**Architecture:** The Android app stays offline-first with Room as its local source of truth and gains a push-then-pull delta sync against a small Kotlin/Ktor backend in `server/`. The backend holds the Supabase service-role key and is the only writer to the database. Every record carries a server-assigned `seq`, an `updated_at`, a `version`, and a `deleted_at` tombstone. Conflicts resolve last-write-wins with a conflict notice returned to the stale writer.

**Tech Stack:** Kotlin 2.2.0, Ktor 3.6.0, kotlinx-serialization 1.11.0, PostgreSQL JDBC 42.7.7, HikariCP 6.3.0, Flyway 11.8.2, zonky embedded-postgres 2.1.0 (tests only), JUnit 4. App side: Android SDK 36 (min 29), Room 2.7.2, Hilt 2.57, Compose 1.8.3, WorkManager 2.7.1.

**Spec:** `docs/superpowers/specs/2026-09-26-family-sync-design.md`

## Global Constraints

- `minSdk = 29`, `targetSdk = 36`, `compileSdk = 36`, Java source/target `17`. Copy verbatim from `build.gradle.kts:22-28,67-68`.
- Dependency versions are pinned inline in `app/build.gradle.kts`. There is no version catalog and none is to be introduced.
- Two new app dependencies, aligned to versions already in use: `testImplementation("androidx.room:room-testing:2.7.2")` and `implementation("androidx.hilt:hilt-work:1.2.0")`.
- Server tests use JUnit 4 and `kotlin-test-junit`, matching the app's existing plain-JUnit style. Do not introduce JUnit 5 on the server.
- **No code comments.** Standing project rule.
- No `runBlocking`, no `!!`, no unsafe casts; use `as?`. `runTest` is the only sanctioned coroutine test runner; never construct `UnconfinedTestDispatcher()` without an explicit shared scheduler.
- Every Gradle invocation follows the monitored `.cmd` wrapper procedure in `AGENTS.md`. Never a blocking foreground call, never two concurrent builds, hard 10-minute cap.
- `AppLockViewModelTest` hangs `:app:testDebugUnitTest` indefinitely and must be excluded from every run.
- No emulator is available. Everything testable must be a pure function over plain data classes, testable on the JVM.
- The Supabase service-role key is a server environment variable only. It must never appear in the app, in the repository, or in any committed file.
- Every remote payload is scoped by `family_id` server-side. A device must never read another family's rows.
- All identifiers are UUIDs carried as strings on the wire and stored as `TEXT` locally. `seq` is a 64-bit value, held as `Long` locally.

---

## Phasing

Phases 1 and 2 are specified here in full and produce a working two-device transaction sync. Phases 3 through 6 are a roadmap; each gets its own detailed plan via the writing-plans skill before it is executed, because the spec deliberately decomposes this into subsystems and one monolithic plan would be unreviewable.

1. Backend service, Supabase schema, family create/join/invite
2. Room migration 16 to 17 and the sync engine, transactions only
3. Budget relocated to `FamilyState`, recompute refactor, per-member limits
4. Remaining tables: periods, categories, tags, recurring, goals, archived
5. UI surfaces, WorkManager background sync, conflict notices
6. Render deployment and documentation

## File Map

### New, backend (`server/`, standalone Gradle build, not part of the Android build)

| File | Responsibility |
|---|---|
| `server/settings.gradle.kts` | Standalone build definition |
| `server/build.gradle.kts` | Ktor, JDBC, Flyway, test deps |
| `server/Dockerfile` | Multi-stage: Gradle build then JRE 17 runtime |
| `server/src/main/resources/db/migration/V1__initial_schema.sql` | All tables, one shared `seq` sequence |
| `server/src/main/kotlin/family/sync/Application.kt` | Ktor module, routing, config, Hikari |
| `server/src/main/kotlin/family/sync/Config.kt` | Env var reading |
| `server/src/main/kotlin/family/sync/db/DatabaseFactory.kt` | Hikari + Flyway bootstrap |
| `server/src/main/kotlin/family/sync/auth/TokenService.kt` | Mint, hash, verify member tokens |
| `server/src/main/kotlin/family/sync/auth/Principal.kt` | Authenticated member + family |
| `server/src/main/kotlin/family/sync/family/FamilyRoutes.kt` | create, invite, join |
| `server/src/main/kotlin/family/sync/sync/SyncRoutes.kt` | `POST /v1/sync` |
| `server/src/main/kotlin/family/sync/sync/ConflictPolicy.kt` | **Pure** last-write-wins resolution |
| `server/src/main/kotlin/family/sync/HealthRoutes.kt` | `GET /health` |
| `server/src/test/kotlin/family/sync/EmbeddedPostgres.kt` | Shared embedded-Postgres fixture and DB helpers |
| `server/src/test/kotlin/family/sync/ServerTestHarness.kt` | `runServer`, `postJson`, `field` helpers for route tests |
| `server/src/test/kotlin/family/sync/HealthRoutesTest.kt` | |
| `server/src/test/kotlin/family/sync/ConflictPolicyTest.kt` | |
| `server/src/test/kotlin/family/sync/InviteRedeemTest.kt` | |
| `server/src/test/kotlin/family/sync/FamilyScopeTest.kt` | |
| `server/src/test/kotlin/family/sync/SchemaMigrationTest.kt` | |

### New, app sync layer (`app/src/main/java/com/danilkinkin/buckwheat/sync/`)

| File | Responsibility |
|---|---|
| `sync/SyncModels.kt` | `WireRecord`, `SyncPushRequest`, `SyncPullResponse`, `ConflictNotice` |
| `sync/SyncMerge.kt` | **Pure** push/pull merge and tombstone application |
| `sync/SyncClient.kt` | HTTP calls, bearer token |
| `sync/SyncEngine.kt` | Orchestrates push then pull |
| `sync/SyncWorker.kt` | WorkManager worker, Hilt-injected |
| `sync/SyncScheduler.kt` | Enqueue and backoff |
| `sync/FamilyRepository.kt` | Family/member local state, token storage |
| `sync/SyncState.kt` | Cursor, last-synced time, in-flight flag |

### New, app UI and entities

| File | Responsibility |
|---|---|
| `settings/family/FamilySettings.kt` | Create, join, members, invite, sync now, disconnect |
| `settings/family/InviteCodeDisplay.kt` | Code plus countdown |
| `home/MemberBudgetProgress.kt` | Per-member sub-budget bars |
| `data/entities/Member.kt` | `id`, `familyId`, `displayName`, `isOwner`, `joinedAt` |
| `data/entities/FamilyState.kt` | `budget`, `startDate`, `finishDate`, `currency` |
| `data/entities/PeriodLimit.kt` | One row per member per period |

### Modified

- `data/entities/Transaction.kt`, `ArchivedTransaction.kt`, `BudgetPeriod.kt`, `SavedCategory.kt`, `SavedTag.kt`, `RecurringTemplate.kt`, `SavingsGoal.kt` — sync columns
- `di/DatabaseModule.kt:184-212` — version 16 to 17 plus migration
- `di/SpendsRepository.kt` — recompute instead of increment (Phase 3)
- `di/AppModule.kt:12-50` — provide `SyncClient`, `SyncEngine`, `FamilyRepository`
- `app/build.gradle.kts` — two new dependencies
- `AndroidManifest.xml` — re-add `ACCESS_NETWORK_STATE`, removed at lines 5-8
- `home/BottomSheets.kt`, `editor/` — member attribution
- `settings/Settings.kt:55-222` — Family section entry

---

# Phase 1: Backend, schema, identity

Verifiable with `curl` alone, before any app code exists.

## Task 1.1: Standalone Gradle build and health endpoint

**Files:**
- Create: `server/settings.gradle.kts`
- Create: `server/build.gradle.kts`
- Create: `server/src/main/kotlin/family/sync/Application.kt`
- Create: `server/src/main/kotlin/family/sync/HealthRoutes.kt`
- Test: `server/src/test/kotlin/family/sync/HealthRoutesTest.kt`

**Interfaces:**
- Produces: `fun Application.familySyncModule()`, `fun Application.configureRouting()`

**Step 1: Write the failing test**

```kotlin
package family.sync

import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun healthReportsOk() = testApplication {
        application { familySyncModule(testing = true) }
        val response = client.get("/health")
        assertEquals(200, response.status.value)
        val body = response.bodyAsText()
        assertEquals(true, body.contains("\"status\":\"ok\""))
    }
}
```

**Step 2: Run it and watch it fail**

Run from `server/`: `gradle test --tests "family.sync.HealthRoutesTest"`
Expected: compilation failure, `unresolved reference: familySyncModule`

**Step 3: Minimal implementation**

`server/settings.gradle.kts`:

```kotlin
rootProject.name = "buckwheat-sync-server"
```

`server/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "family.sync"
version = "0.1.0"

repositories { mavenCentral() }

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("family.sync.ApplicationKt")
}

tasks.test { useJUnit() }
```

`HealthRoutes.kt`:

```kotlin
package family.sync

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureHealth() {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
```

`Application.kt`:

```kotlin
package family.sync

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

fun main() {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.familySyncModule(testing: Boolean = false) {
    install(ContentNegotiation) { json() }
    configureHealth()
}
```

**Step 4: Run and verify it passes**

`gradle test --tests "family.sync.HealthRoutesTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add server
git commit -m "build(server): standalone Ktor build with health endpoint"
```

## Task 1.2: Database config and Flyway schema

**Files:**
- Create: `server/src/main/kotlin/family/sync/Config.kt`
- Create: `server/src/main/kotlin/family/sync/db/DatabaseFactory.kt`
- Create: `server/src/main/resources/db/migration/V1__initial_schema.sql`
- Create: `server/src/test/kotlin/family/sync/EmbeddedPostgres.kt`
- Test: `server/src/test/kotlin/family/sync/SchemaMigrationTest.kt`

**Interfaces:**
- Produces: `data class Config(val databaseUrl: String, val databaseUser: String, val databasePassword: String)`
- Produces: `fun loadConfig(env: Map<String, String>): Config`
- Produces: `fun createDataSource(config: Config): DataSource`

**Step 1: Write the failing test**

```kotlin
package family.sync

import family.sync.db.createDataSource
import javax.sql.DataSource
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaMigrationTest {
    @Test
    fun migrationCreatesEverySyncedTable() {
        val dataSource: DataSource = TestDatabase.dataSource
        dataSource.connection.use { connection ->
            val tables = mutableSetOf<String>()
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) tables.add(rs.getString("TABLE_NAME").lowercase())
            }
            listOf(
                "families", "members", "invites", "sync_sequence",
                "transactions", "archived_transactions", "budget_periods",
                "saved_categories", "saved_tags", "recurring_templates", "savings_goals",
            ).forEach { assertTrue(it in tables, "missing table $it") }
        }
    }

    @Test
    fun everySyncedTableCarriesTheSyncColumns() {
        val expected = setOf("family_id", "seq", "updated_at", "version", "deleted_at")
        val dataSource: DataSource = TestDatabase.dataSource
        dataSource.connection.use { connection ->
            listOf("transactions", "saved_tags", "savings_goals").forEach { table ->
                val columns = mutableSetOf<String>()
                connection.metaData.getColumns(null, "public", table, "%").use { rs ->
                    while (rs.next()) columns.add(rs.getString("COLUMN_NAME").lowercase())
                }
                assertTrue(expected.all { it in columns }, "$table missing ${expected - columns}")
            }
        }
    }

    @Test
    fun aSingleSequenceBacksEverySyncedTable() {
        val dataSource: DataSource = TestDatabase.dataSource
        dataSource.connection.use { connection ->
            val defaults = mutableSetOf<String>()
            listOf("transactions", "archived_transactions", "saved_categories").forEach { table ->
                connection.createStatement().use { st ->
                    st.executeQuery("select column_default from information_schema.columns where table_name = '$table' and column_name = 'seq'").use { rs ->
                        if (rs.next()) defaults.add(rs.getString(1))
                    }
                }
            }
            assertEquals(1, defaults.size, "seq defaults differ per table: $defaults")
            assertTrue(defaults.first().contains("sync_sequence"), "unexpected default ${defaults.first()}")
        }
    }

    companion object {
        private val dataSource: DataSource get() = TestDatabase.dataSource
    }
}
```

**Step 2: Run it and watch it fail**

`gradle test --tests "family.sync.SchemaMigrationTest"`
Expected: compilation failure, `unresolved reference: EmbeddedPostgresInstance`

**Step 3: Minimal implementation**

`EmbeddedPostgres.kt`:

```kotlin
package family.sync

import family.sync.db.createDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import javax.sql.DataSource

class EmbeddedPostgresInstance(val dataSource: DataSource, private val postgres: EmbeddedPostgres) :
    AutoCloseable {
    override fun close() = postgres.close()
}

fun startEmbeddedPostgres(): EmbeddedPostgresInstance {
    val dataDirectory = File(System.getProperty("java.io.tmpdir"), "buckwheat-sync-pg-${System.nanoTime()}")
    val postgres = EmbeddedPostgres.builder()
        .setServerConfig("max_connections", "20")
        .setServerConfig("fsync", "off")
        .setDataDirectory(dataDirectory)
        .start()
    return EmbeddedPostgresInstance(createDataSource(postgres.jdbcUrl, "postgres", ""), postgres)
}
```

The three database-backed suites in Tasks 1.2, 1.3 and 1.4 all share one fixture, because starting a Postgres instance per class is slow. Extend `EmbeddedPostgres.kt` with these helpers, which is what the tests in those tasks call:

```kotlin
data class TestFamily(val familyId: String, val memberId: String)

object TestDatabase {
    private val instance: EmbeddedPostgresInstance by lazy { startEmbeddedPostgres() }

    val dataSource: DataSource get() = instance.dataSource

    fun truncateAll() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "truncate invites, member_tokens, members, families, transactions, " +
                        "archived_transactions, budget_periods, family_state, period_limits, " +
                        "saved_categories, saved_tags, recurring_templates, savings_goals, " +
                        "family_settings restart identity cascade"
                )
            }
        }
    }

    fun createFamily(tokens: family.sync.auth.TokenService, displayName: String): TestFamily {
        val familyId = insertUuid("insert into families default values returning id")
        val memberId = insertUuid(
            "insert into members (family_id, display_name, is_owner) values ('$familyId', '$displayName', true) returning id"
        )
        return TestFamily(familyId, memberId)
    }

    fun readTokenHashes(): List<String> {
        val hashes = mutableListOf<String>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select token_hash from member_tokens").use { rs ->
                    while (rs.next()) hashes.add(rs.getString(1))
                }
            }
        }
        return hashes
    }

    fun expireInvite(code: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("update invites set expires_at = now() - interval '1 minute' where code = ?")
                .use { it.setString(1, code); it.executeUpdate() }
        }
    }

    private fun insertUuid(sql: String): String {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> rs.next(); return rs.getString(1) }
            }
        }
    }
}
```

Task 1.4 additionally needs HTTP helpers, because its suite drives the real routes rather than the store. Add `server/src/test/kotlin/family/sync/ServerTestHarness.kt`:

```kotlin
package family.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

fun runServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    TestDatabase.truncateAll()
    application { familySyncModule() }
    block()
}

suspend fun ApplicationTestBuilder.postJson(path: String, body: String, token: String? = null): HttpResponse =
    client.post(path) {
        contentType(ContentType.Application.Json)
        token?.let { header("Authorization", "Bearer $it") }
        setBody(body)
    }

fun field(response: HttpResponse, name: String): String =
    Json.parseToJsonElement(response.bodyAsText())
        .let { it as JsonObject }
        .getValue(name)
        .jsonPrimitive
        .content
```

`Config.kt`:

```kotlin
package family.sync

data class Config(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val port: Int = 8080,
)

fun loadConfig(env: Map<String, String>): Config = Config(
    databaseUrl = env["DATABASE_URL"] ?: error("DATABASE_URL is required"),
    databaseUser = env["DATABASE_USER"] ?: "postgres",
    databasePassword = env["DATABASE_PASSWORD"] ?: "",
    port = env["PORT"]?.toInt() ?: 8080,
)
```

`DatabaseFactory.kt`:

```kotlin
package family.sync

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway

fun createDataSource(jdbcUrl: String, user: String, password: String): DataSource {
    val hikari = HikariConfig()
    hikari.jdbcUrl = jdbcUrl
    hikari.username = user
    hikari.password = password
    hikari.maximumPoolSize = 10
    return HikariDataSource(hikari)
}

fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}
```

`V1__initial_schema.sql`:

```sql
create extension if not exists pgcrypto;

create sequence if not exists sync_sequence;

create table families (
    id uuid primary key default gen_random_uuid(),
    created_at timestamptz not null default now()
);

create table members (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    display_name text not null,
    is_owner boolean not null default false,
    joined_at timestamptz not null default now()
);

create table invites (
    code text primary key,
    family_id uuid not null references families(id) on delete cascade,
    created_by uuid not null references members(id) on delete cascade,
    expires_at timestamptz not null,
    redeemed_at timestamptz
);

create table member_tokens (
    token_hash text primary key,
    member_id uuid not null references members(id) on delete cascade,
    family_id uuid not null references families(id) on delete cascade,
    created_at timestamptz not null default now()
);

create table transactions (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    member_id uuid references members(id) on delete set null,
    type text not null,
    value numeric not null,
    spent_at bigint not null,
    comment text not null,
    category text,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table budget_periods (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    budget numeric not null,
    start_date bigint not null,
    finish_date bigint not null,
    actual_finish_date bigint,
    currency text not null,
    total_spent numeric not null default 0,
    is_imported boolean not null default false,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table archived_transactions (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    member_id uuid references members(id) on delete set null,
    period_id uuid not null references budget_periods(id) on delete cascade,
    type text not null,
    value numeric not null,
    spent_at bigint not null,
    comment text not null,
    category text,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table family_state (
    family_id uuid primary key references families(id) on delete cascade,
    budget numeric not null,
    start_date bigint not null,
    finish_date bigint not null,
    currency text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1
);

create table period_limits (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    period_id uuid not null references budget_periods(id) on delete cascade,
    member_id uuid not null references members(id) on delete cascade,
    limit_value numeric not null,
    unique (period_id, member_id)
);

create table saved_categories (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    name text not null,
    emoji text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table saved_tags (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    name text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table recurring_templates (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    amount numeric not null,
    comment text not null,
    day_of_month integer not null,
    enabled boolean not null default true,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table savings_goals (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    target numeric not null,
    current numeric not null default 0,
    deadline bigint,
    created_at bigint not null,
    completed boolean not null default false,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint
);

create table family_settings (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references families(id) on delete cascade,
    key text not null,
    value text not null,
    seq bigint not null default nextval('sync_sequence'),
    updated_at bigint not null default 0,
    version integer not null default 1,
    deleted_at bigint,
    unique (family_id, key)
);

create index on transactions (family_id, seq);
create index on archived_transactions (family_id, seq);
create index on budget_periods (family_id, seq);
create index on saved_categories (family_id, seq);
create index on saved_tags (family_id, seq);
create index on recurring_templates (family_id, seq);
create index on savings_goals (family_id, seq);
create index on member_tokens (member_id);
create index on invites (family_id);
```

Note the ordering constraint: `archived_transactions` references `budget_periods`, so `budget_periods` must be created first. The ordering above is correct.

**Step 4: Run and verify it passes**

`gradle test --tests "family.sync.SchemaMigrationTest"`
Expected: PASS, 3 tests

**Step 5: Commit**

```bash
git add server
git commit -m "feat(server): schema for families, members, invites and sync"
```

## Task 1.3: Token service and family scoping

**Files:**
- Create: `server/src/main/kotlin/family/sync/auth/TokenService.kt`
- Create: `server/src/main/kotlin/family/sync/auth/Principal.kt`
- Test: `server/src/test/kotlin/family/sync/FamilyScopeTest.kt`

**Interfaces:**
- Produces: `data class Principal(val memberId: String, val familyId: String)`
- Produces: `class TokenService(private val dataSource: DataSource) { fun mint(memberId: String, familyId: String): String; fun verify(token: String): Principal? }`

**Step 1: Write the failing test**

```kotlin
package family.sync

import family.sync.auth.TokenService
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FamilyScopeTest {
    @Test
    fun aValidTokenResolvesToItsMemberAndFamily() {
        val service = TokenService(TestDatabase.dataSource)
        val family = TestDatabase.createFamily(service, "one")
        val token = service.mint(family.memberId, family.familyId)
        val principal = assertNotNull(service.verify(token))
        assertEquals(family.memberId, principal.memberId)
        assertEquals(family.familyId, principal.familyId)
    }

    @Test
    fun aTamperedTokenIsRefused() {
        val service = TokenService(TestDatabase.dataSource)
        val family = TestDatabase.createFamily(service, "one")
        val token = service.mint(family.memberId, family.familyId)
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertNull(service.verify(tampered))
    }

    @Test
    fun anUnknownTokenIsRefused() {
        val service = TokenService(TestDatabase.dataSource)
        assertNull(service.verify("not-a-real-token"))
    }

    @Test
    fun aTokenIsNeverStoredInPlainText() {
        val service = TokenService(TestDatabase.dataSource)
        val family = TestDatabase.createFamily(service, "one")
        val token = service.mint(family.memberId, family.familyId)
        val stored = TestDatabase.readTokenHashes()
        assertEquals(false, stored.any { it == token })
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(true, stored.contains(digest))
    }

    @Test
    fun aTokenFromOneFamilyCannotReachAnother() {
        val service = TokenService(TestDatabase.dataSource)
        val first = TestDatabase.createFamily(service, "first")
        val second = TestDatabase.createFamily(service, "second")
        val firstPrincipal = assertNotNull(service.verify(service.mint(first.memberId, first.familyId)))
        val secondPrincipal = assertNotNull(service.verify(service.mint(second.memberId, second.familyId)))
        assertEquals(false, firstPrincipal.familyId == secondPrincipal.familyId)
    }
}
```

**Step 2: Run it and watch it fail**

`gradle test --tests "family.sync.FamilyScopeTest"`
Expected: compilation failure, `unresolved reference: TokenService`

**Step 3: Minimal implementation**

`Principal.kt`:

```kotlin
package family.sync.auth

data class Principal(val memberId: String, val familyId: String)
```

`TokenService.kt`:

```kotlin
package family.sync.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.sql.DataSource

class TokenService(private val dataSource: DataSource) {

    fun mint(memberId: String, familyId: String): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "insert into member_tokens (token_hash, member_id, family_id) values (?, ?, ?)"
            ).use { statement ->
                statement.setString(1, hash(token))
                statement.setString(2, memberId)
                statement.setString(3, familyId)
                statement.executeUpdate()
            }
        }
        return token
    }

    fun verify(token: String): Principal? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select member_id, family_id from member_tokens where token_hash = ?"
            ).use { statement ->
                statement.setString(1, hash(token))
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return Principal(rs.getString("member_id"), rs.getString("family_id"))
                }
            }
        }
    }

    private fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
```

**Step 4: Run and verify it passes**

`gradle test --tests "family.sync.FamilyScopeTest"`
Expected: PASS, 5 tests

**Step 5: Commit**

```bash
git add server
git commit -m "feat(server): hashed member tokens scoped to one family"
```

## Task 1.4: Family create, invite, join

**Files:**
- Create: `server/src/main/kotlin/family/sync/family/FamilyRoutes.kt`
- Modify: `server/src/main/kotlin/family/sync/Application.kt`
- Test: `server/src/test/kotlin/family/sync/InviteRedeemTest.kt`

**Interfaces:**
- Produces: `POST /v1/family/create` body `{"displayName": String}` to `{"familyId": String, "memberId": String, "token": String}`
- Produces: `POST /v1/family/invite` header `Authorization: Bearer <token>` to `{"code": String, "expiresAt": String}`
- Produces: `POST /v1/family/join` body `{"code": String, "displayName": String}` to `{"familyId": String, "memberId": String, "token": String}`

**Step 1: Write the failing test**

```kotlin
package family.sync

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InviteRedeemTest {
    @Test
    fun theOwnerCanMintAnInviteAndAnotherDeviceCanRedeemIt() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        val token = field(owner, "token")

        val invite = postJson("/v1/family/invite", "{}", token)
        val code = field(invite, "code")

        val joined = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")
        assertEquals(200, joined.status.value)
        assertEquals(field(owner, "familyId"), field(joined, "familyId"))
        assertTrue(field(joined, "memberId") != field(owner, "memberId"))
    }

    @Test
    fun anInviteCodeIsSingleUse() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        val code = field(postJson("/v1/family/invite", "{}", field(owner, "token")), "code")
        assertEquals(200, postJson("/v1/family/join", """{"code":"$code","displayName":"a"}""").status.value)
        val second = postJson("/v1/family/join", """{"code":"$code","displayName":"b"}""")
        assertEquals(409, second.status.value)
    }

    @Test
    fun anExpiredInviteCodeIsRefused() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        val code = field(postJson("/v1/family/invite", "{}", field(owner, "token")), "code")
        expireInvite(code)
        assertEquals(410, postJson("/v1/family/join", """{"code":"$code","displayName":"late"}""").status.value)
    }

    @Test
    fun anUnknownInviteCodeIsRefused() = runServer {
        assertEquals(404, postJson("/v1/family/join", """{"code":"nope","displayName":"x"}""").status.value)
    }

    @Test
    fun anUnauthenticatedDeviceCannotMintAnInvite() = runServer {
        val response = postJson("/v1/family/invite", "{}", null)
        assertEquals(401, response.status.value)
    }

    @Test
    fun aNonOwnerCannotMintAnInvite() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        val code = field(postJson("/v1/family/invite", "{}", field(owner, "token")), "code")
        val child = postJson("/v1/family/join", """{"code":"$code","displayName":"child"}""")
        assertEquals(403, postJson("/v1/family/invite", "{}", field(child, "token")).status.value)
    }

    @Test
    fun aTamperedTokenCannotMintAnInvite() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        val token = field(owner, "token")
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertEquals(401, postJson("/v1/family/invite", "{}", tampered).status.value)
    }

    @Test
    fun theCreatedMemberIsTheOwner() = runServer {
        val owner = postJson("/v1/family/create", """{"displayName":"parent"}""")
        assertEquals(200, owner.status.value)
        assertTrue(field(owner, "token").isNotBlank())
    }
}
```

**Step 2: Run it and watch it fail**

`gradle test --tests "family.sync.InviteRedeemTest"`
Expected: compilation failure, `unresolved reference: runServer`

**Step 3: Minimal implementation**

`FamilyRoutes.kt`:

```kotlin
package family.sync.family

import family.sync.auth.Principal
import family.sync.auth.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private const val INVITE_LIFETIME_MINUTES = 15L

data class CreateFamilyRequest(val displayName: String)
data class JoinFamilyRequest(val code: String, val displayName: String)
data class FamilyCreated(val familyId: String, val memberId: String, val token: String)
data class InviteCreated(val code: String, val expiresAt: String)

class FamilyStore(private val dataSource: javax.sql.DataSource, private val tokens: TokenService) {

    fun createFamily(displayName: String): FamilyCreated = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val familyId = insertReturningId(
                connection, "insert into families default values returning id"
            )
            val memberId = insertReturningId(
                connection,
                "insert into members (family_id, display_name, is_owner) values (?, ?, true) returning id",
                familyId, displayName
            )
            connection.commit()
            FamilyCreated(familyId, memberId, tokens.mint(memberId, familyId))
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    fun createInvite(principal: Principal): InviteCreated = dataSource.connection.use { connection ->
        val code = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val expiresAt = Instant.now().plusSeconds(INVITE_LIFETIME_MINUTES * 60)
        connection.prepareStatement(
            "insert into invites (code, family_id, created_by, expires_at) values (?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, code)
            statement.setString(2, principal.familyId)
            statement.setString(3, principal.memberId)
            statement.setTimestamp(4, Timestamp.from(expiresAt))
            statement.executeUpdate()
        }
        InviteCreated(code, expiresAt.toString())
    }

    fun joinFamily(code: String, displayName: String): FamilyCreated? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "select family_id, expires_at, redeemed_at from invites where code = ? for update"
            ).use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val familyId = rs.getString("family_id")
                    val expiresAt = rs.getTimestamp("expires_at").toInstant()
                    val redeemedAt = rs.getTimestamp("redeemed_at")
                    if (redeemedAt != null || expiresAt.isBefore(Instant.now())) {
                        connection.rollback()
                        return@use null
                    }
                    val memberId = insertReturningId(
                        connection,
                        "insert into members (family_id, display_name) values (?, ?) returning id",
                        familyId, displayName
                    )
                    connection.prepareStatement("update invites set redeemed_at = now() where code = ?")
                        .use { it.setString(1, code); it.executeUpdate() }
                    connection.commit()
                    FamilyCreated(familyId, memberId, tokens.mint(memberId, familyId))
                }
            }
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    fun isOwner(principal: Principal): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("select is_owner from members where id = ?").use { statement ->
            statement.setString(1, principal.memberId)
            statement.executeQuery().use { rs -> rs.next() && rs.getBoolean("is_owner") }
        }
    }

    private fun insertReturningId(
        connection: java.sql.Connection,
        sql: String,
        vararg args: String,
    ): String = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }
}
```

Wire it into `Application.kt` with a `principal` helper that reads the bearer header, and a `StatusPages` handler mapping failures to 400/401/403/404/409/410 so `InviteRedeemTest`'s expectations hold.

**Step 4: Run and verify it passes**

`gradle test --tests "family.sync.InviteRedeemTest"`
Expected: PASS, 8 tests

**Step 5: Commit**

```bash
git add server
git commit -m "feat(server): family create, single-use invites, and join"
```

## Task 1.5: Wire config and run the server for real

**Files:**
- Modify: `server/src/main/kotlin/family/sync/Application.kt`
- Create: `render.yaml`

**Step 1: Add env-driven config to the non-testing entry point**

Read `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` via `loadConfig(System.getenv())`, build the data source, run `migrate(dataSource)`, then install the family and health routes. When `testing = true`, accept an injected data source instead.

**Step 2: Verify against a real database**

Start the server against a local Postgres, then:

```
curl -s localhost:8080/health
```

Expected `{"status":"ok"}`

Then create a family and confirm a token comes back:

```
curl -s -X POST localhost:8080/v1/family/create -H "Content-Type: application/json" -d '{"displayName":"parent"}'
```

**Step 3: Commit**

```bash
git add server render.yaml
git commit -m "feat(server): env-driven config and Render blueprint"
```

---

# Phase 2: Room migration and the sync engine, transactions only

Specified here at task level with the exact interfaces. Full step-by-step expansion via the writing-plans skill happens after Phase 1 lands, so it can incorporate real feedback from a running backend.

## Task 2.1: Dependencies and manifest

**Files:**
- Modify: `app/build.gradle.kts` — add `testImplementation("androidx.room:room-testing:2.7.2")` and `implementation("androidx.hilt:hilt-work:1.2.0")`
- Modify: `app/src/main/AndroidManifest.xml` — re-add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`

**Verify:** `gradlew compileDebugKotlin` succeeds. The manifest change is needed so the app can tell offline from online before enqueuing sync.

## Task 2.2: Sync columns and Room 16 to 17

**Files:**
- Modify: `data/entities/Transaction.kt`, `ArchivedTransaction.kt`, `BudgetPeriod.kt`, `SavedCategory.kt`, `SavedTag.kt`, `RecurringTemplate.kt`, `SavingsGoal.kt`
- Create: `data/entities/Member.kt`, `FamilyState.kt`, `PeriodLimit.kt`
- Modify: `di/DatabaseModule.kt:184-212`

**Produces:** `family_id TEXT`, `sync_seq INTEGER NOT NULL DEFAULT 0`, `updated_at INTEGER NOT NULL DEFAULT 0`, `deleted_at INTEGER NULL`, `version INTEGER NOT NULL DEFAULT 1` on all seven; `member_id TEXT NULL` on `Transaction` and `ArchivedTransaction`; three new tables `members`, `family_state`, `period_limits`.

**Test:** `Migration16To17Test` using `MigrationTestHelper` against `app/schemas`. Create a v16 database, insert a transaction, a tag and a goal, migrate, assert all three rows survived with correct defaults and that `family_id` is null.

## Task 2.3: `SyncMerge`, the pure core

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/sync/SyncModels.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/sync/SyncMerge.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/sync/SyncMergeTest.kt`

**Produces:**

```kotlin
data class WireRecord(
    val table: String,
    val id: String,
    val seq: Long,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
)

data class LocalRecord(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val version: Int,
    val deletedAt: Long?,
    val payload: String,
    val dirty: Boolean,
)

data class ConflictNotice(val table: String, val id: String, val wonByMemberId: String)

data class MergeResult(
    val records: List<LocalRecord>,
    val cursor: Long,
    val conflicts: List<ConflictNotice>,
)

fun mergePull(local: List<LocalRecord>, remote: List<WireRecord>, cursor: Long): MergeResult
```

**Rules to implement and test:** a remote record with a newer `updatedAt` replaces local; a local record that is newer produces a `ConflictNotice` and is replaced; a remote tombstone always applies; the returned cursor is the maximum `seq` seen, or the input cursor when remote is empty; dirty local records are never clobbered by an older remote.

**Write `SyncMergeTest` first and watch it fail.** This is the highest-value test file in the project.

## Task 2.4: `SyncClient` and `SyncEngine`

**Files:**
- Create: `app/src/main/java/com/danilkinkin/buckwheat/sync/SyncClient.kt`
- Create: `app/src/main/java/com/danilkinkin/buckwheat/sync/SyncEngine.kt`
- Test: `app/src/test/java/com/danilkinkin/buckwheat/sync/SyncEngineTest.kt`

**Produces:** `class SyncEngine(private val client: SyncClient, private val database: SyncDatabase) { suspend fun sync(): SyncOutcome }`

**Test with a fake `SyncClient`:** asserts push happens before pull, a push failure leaves the cursor untouched, and the whole apply runs inside one Room transaction.

## Task 2.5: Enrol the device

**Files:**
- Modify: `di/SpendsRepository.kt`
- Modify: `di/AppModule.kt:12-50`

On enrolment, existing transactions get the owner's `member_id`, the family id, and a clean sync state. Only `addSpent`, `removeSpent` and CSV import mark rows dirty at this stage.

---

# Phases 3 to 6: roadmap

Each requires its own detailed plan before execution.

- **Phase 3** — `BudgetRecompute` as a pure function with `BudgetRecomputeTest` written first; `FamilyState` replaces the budget DataStore keys; `SpendsRepository` recomputes instead of incrementing; `PeriodLimit` enforces that member limits sum exactly to the pool. Highest-risk phase, gated behind pure tests.
- **Phase 4** — repeat the Phase 2 merge shape for `budget_periods`, `saved_categories`, `saved_tags`, `recurring_templates`, `savings_goals`, `archived_transactions`. Needs explicit tests for the `period_id` cascade delete interacting with soft-delete tombstones, which is the likeliest resurrection bug in the project.
- **Phase 5** — `SyncWorker` with `@HiltWorker`, `SyncScheduler` with exponential backoff, `FamilySettings.kt`, `MemberBudgetProgress.kt`, member picker in the editor, conflict snackbar.
- **Phase 6** — `server/Dockerfile`, `render.yaml` with health check, README, and the manual two-device acceptance run that development cannot perform.
