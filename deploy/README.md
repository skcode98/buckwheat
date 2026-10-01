# Deploying family sync

The sync service is **separate from the Android app on purpose**. That is the
right architecture, and it is also what already exists in this repository — not
something being introduced here.

- The Android app is a plain HTTP client. It talks to `/v1/sync` and
  `/v1/family/<action>` on whatever base URL the user types into the Family Sync
  sheet, over HTTPS, with a `Bearer` token. It hard-codes no host, no port, no
  scheme and no database. `HttpSyncClient.syncEndpoint` appends `/v1/sync` to the
  stored base URL; `HttpFamilyApi.familyEndpoint` appends `/v1/family/<action>`.
  Nothing else in the app knows where the server lives.
- Because there is no baked-in address, one built APK works against a local
  server, a staging Render service and production. Pointing a family at a
  different deployment is a text field, not a release.
- The server lives in `server/` with its own `settings.gradle.kts`. It is not a
  subproject of the Android build; the repository-root `settings.gradle.kts`
  includes only `:app`. That is what lets it be built and deployed independently
  of the Android app.
- You can absolutely keep the deployment material in this same project
  directory. That is what this `deploy/` folder is. Nothing about hosting the
  service elsewhere requires moving the repository or the code.

## One hard constraint forced the split

The repository root `build.gradle.kts` applies the Android application and KSP
plugins. Render's native build runtimes always build at the repository root, so
a native Render build would try to compile the Android app and fail with no
Android SDK present. `server/Dockerfile` sidesteps that by copying only
`server/` into the image, which is the entire reason for the Docker
indirection — not packaging preference.

## Contents

| File | What it covers |
|---|---|
| `deploy/README.md` | This file. The full Supabase + Render walkthrough. |
| `deploy/env.example` | Every environment variable the server reads. |
| `deploy/OPERATIONS.md` | Day two: rotating secrets, redeploys, rollbacks, logs, the free-tier pause. |
| `deploy/supabase/README.md` | Database setup and the row-level-security story. |
| `deploy/supabase/verify.sql` | Read-only sanity queries you can paste into the Supabase SQL editor. |

Outside this folder: `render.yaml` at the repository root is the Render
blueprint, and `server/Dockerfile` builds the image.

## Costs

Both tiers are $0 and neither needs a credit card.

| | |
|---|---|
| Render web service (free) | 512 MB RAM. **Spins down after 15 minutes with no inbound traffic**; the next request wakes it, which takes about a minute. The whole workspace shares 750 free instance hours per calendar month. |
| Supabase project (free) | 500 MB database. **Pauses after 7 days of low activity**, data retained. Waking it is a manual **Resume project** click in the dashboard, not automatic. |

Both preserve data, so nothing is lost. But they are different failure modes and
the difference is felt: a Render sleep resolves itself on the next request,
whereas a paused Supabase project stays down until somebody opens the dashboard.
See the "Free-tier behaviour" section below and `OPERATIONS.md`.

---

# Walkthrough

## 1. Create the Supabase project

1. Sign in at <https://supabase.com/dashboard> and create a new project. Choose
   a region near your family. A strong database password is the single most
   important secret in this whole setup.
2. Wait for provisioning to finish.
3. Open the **Connect** dialog (or **Project Settings → Database**). You will
   see several connection strings. Read `deploy/supabase/README.md` before
   choosing one — the choice matters and the wrong one fails in a confusing way.

## 2. Copy the connection details

You need two values:

| Render env var | Where it comes from |
|---|---|
| `DATABASE_URL` | The **session pooler** URI, port **5432** |
| `DATABASE_PASSWORD` | The database password you chose in step 1 |

Render prompts for exactly these two. `DATABASE_USER` is deliberately absent
from `render.yaml`: it is an override the service does not need, and prompting
for it only invites an operator to type a bare `postgres` and break the
connection (see the warning below).

The session pooler string looks like:

```
postgresql://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
```

**Use port 5432, not 6543.** Flyway needs real sessions. Supabase's transaction
pooler on 6543 does not support session-level features — advisory locks among
them — and Flyway relies on them. The port number is the whole difference
between the two modes on the same host.

**The username is `postgres.<project-ref>`.** The shared pooler identifies
which project you mean from the username. Sending a bare `postgres` fails at
boot with:

```
FATAL: (ENOTFOUND) tenant/user postgres. not found
```

`DATABASE_URL` already carries that username, and `Config.kt` reads it out of
the connection string, so pasting the string verbatim is enough. Set
`DATABASE_USER` only if you need to override it — the value there wins when
present. The password is still stripped from the URL (Hikari supplies it), so
that never leaks into a logged connection URL.

If you were to send a bare `postgres` anyway, the server falls back to `postgres`
and you get the error above.

If you would rather avoid the pooler entirely, use the **direct** connection
string on port 5432 instead. That one does take a bare `postgres` — but the
direct host is IPv6-only on the free plan, and Render's outbound networking is
IPv4, so the direct string generally will not connect from Render on a free
project. The session pooler is the right answer here.

## 3. Nothing to paste into the database — yet

Do **not** run any SQL by hand. The server runs Flyway on boot from
`server/src/main/resources/db/migration/` and applies:

- `V1__initial_schema.sql` — the 14 family tables
- `V2__savings_goals_name.sql` — adds `savings_goals.name`
- `V3__lock_down_public_access.sql` — enables row-level security on all 14

The first boot therefore *is* the migration. Expect roughly a second of extra
startup time before the health endpoint answers.

## 4. Push, then create the blueprint

Render builds from GitHub, so commit and push first — `render.yaml`, the
Dockerfile, `.dockerignore` and this folder all need to be on the branch.

1. Render dashboard → **Blueprints** → **New Blueprint Instance**.
2. Repository: `skcode98/buckwheat`, branch `master`.
3. Blueprint path: the repository-root `render.yaml` (Render looks there by
   default, so you can leave the field alone).
4. When prompted for values, supply the three from step 2. `DATABASE_SSL_MODE`
   is already pinned to `require` in the blueprint.

Render creates a web service named `family-sync`, builds the Docker image, and
only then starts the container.

## 5. Verify

Copy the URL Render shows you on the service's dashboard — it looks like
`https://family-sync.onrender.com`, but Render appends a suffix if that name is
already taken globally, so do not assume it.

```bash
curl https://<your-render-url>/health
```

Expected: `200` with `{"status":"ok"}`. This route needs no authentication and
does not touch the database — it exists for Render's own health probe. The first
request after an idle period can take up to about a minute while the free
instance spins back up.

Then prove the service can actually reach Postgres, by creating a family:

```bash
curl -X POST https://<your-render-url>/v1/family/create \
  -H "Content-Type: application/json" \
  -d '{"displayName":"deploy-check"}'
```

Expected: `200` with `familyId`, `memberId` and a `token`. If this returns
`tenant/user postgres. not found`, the username in `DATABASE_URL` is a bare
`postgres` (step 2). If it returns `400` with `displayName_required`, the server
is up and your JSON is wrong — which is also a success for this check.

Check the migrations landed:

```sql
select version, description, success
from flyway_schema_history
order by installed_rank;
```

Three rows, `success = true`. See `deploy/supabase/verify.sql` for more.

# Wire contract

Authoritative source is the code, not this document:

| Concern | Where |
|---|---|
| Route table | `server/src/main/kotlin/family/sync/family/FamilyRoutes.kt`, `sync/SyncRoutes.kt`, `HealthRoutes.kt` |
| Auth | `Authorization: Bearer <token>` on every route except `/health`; tokens are SHA-256 hashed before storage |
| Error shape | `{"error": "<code>"}` via the `StatusPages` block in `server/src/main/kotlin/family/sync/Application.kt` |
| Client side | `app/src/main/java/com/danilkinkin/buckwheat/sync/HttpSyncClient.kt`, `HttpFamilyApi.kt` |

Routes, all `POST` except `/health`, which is `GET`:

| Path | Auth | Purpose |
|---|---|---|
| `GET /health` | none | liveness, Render health check |
| `POST /v1/family/create` | none | create a family, returns ids and a token |
| `POST /v1/family/join` | none | redeem an invite code, returns ids and a token |
| `POST /v1/family/invite` | bearer | mint a fresh invite code |
| `POST /v1/family/whoami` | bearer | resolve the current member |
| `POST /v1/family/members` | bearer | list the family's display names |
| `POST /v1/sync` | bearer | push and pull changes |

Note there is no `POST /v1/family` — enrolment goes through `/create` and
`/join`.

# Free-tier behaviour to expect

| | |
|---|---|
| Render web service | Free, forever. 512 MB. Spins down after 15 min idle, ~1 min to wake. |
| Supabase project | Free, never expires. 500 MB. **Pauses after 7 days of low activity**, data kept. |

A family that uses the app every day will not notice either: the app's sync
interval generates steady database traffic, which keeps Supabase awake, and every
sync attempt keeps Render awake. The rough edges are (a) the first sync after a
long gap can fail once while Render wakes, and the app retries later, and (b) a
paused Supabase project needs a manual **Resume project** click.

`OPERATIONS.md` covers both in detail.

# Enrolling a family

Open Settings and pick "Family sync". The sheet has two sides.

To start a family: enter the server URL (the Render URL, `https://…onrender.com`),
type a display name, then tap create. The app creates the family, schedules
background sync, and shows the family and member ids.

To join one: paste the code a family member gave you into the invite field, enter
a display name, then tap join.

Once enrolled the sheet shows the family and member ids and offers a button to
mint a new invite code, which appears on screen to read out or share. Signing out
stops background sync and forgets the family on this device.