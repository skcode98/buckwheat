# Family sync — deployment

The sync server lives in `server/` and is deployed to **Render** (free web service),
backed by a **Supabase** free Postgres project. Both free tiers are $0 and neither
needs a credit card.

## Why Docker

Render builds on its own infrastructure, so **you do not need Docker installed locally**.
Docker is also the right runtime here because the repository root is the *Android*
build (`build.gradle.kts` applies the Android and KSP plugins). A native Render build
always runs at the repo root and would try to compile the Android app, which fails
without an Android SDK. `server/Dockerfile` copies only `server/` and sidesteps that
entirely.

`render.yaml` points at it with `dockerfilePath: ./server/Dockerfile`.

## 1. Create the Supabase project

1. Go to <https://supabase.com/dashboard> and create a new project.
2. Wait for the database to finish provisioning.
3. Open **Connect** (or **Project Settings → Database**) and copy these three values:
   - `DATABASE_URL` — the **session-mode pooler** URI on port **5432**, not `6543`.
     Flyway migrations need real sessions, so transaction-mode pooling will not work.
     The URI looks like
     `postgresql://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`
   - `DATABASE_USER` — `postgres`
   - `DATABASE_PASSWORD` — your project database password
4. Nothing else. You do **not** need to paste any SQL: the server runs Flyway on boot
   and applies `server/src/main/resources/db/migration/` (V1 schema, V2 goals name, V3
   row-level security) automatically.

Migration V3 enables row-level security on all 14 family tables. Supabase exposes the
`public` schema through its auto-generated API, so without RLS anyone holding the anon
key could read the whole family database. RLS with no policies blocks the anon and
authenticated roles while leaving the server's own connection unaffected.

## 2. Launch the Render blueprint

Render builds from GitHub, so **commit and push first**, then:

1. Render dashboard → **Blueprints** → **New Blueprint Instance**
2. Repository: `skcode98/buckwheat`, branch `master`
3. Blueprint path: the repo-root `render.yaml`
4. When prompted, paste the three values from step 1. `DATABASE_SSL_MODE` is already
   fixed to `require` in the blueprint.

Render then creates the `family-sync` web service and builds the Docker image. First boot
runs the migrations; watch the deploy logs for `Started` and then hit the health endpoint.

## 3. Verify

```bash
curl https://family-sync.onrender.com/health
```

Expected: a 200 with the service healthy. The first request after an idle period can take
up to ~a minute while the free instance spins back up.

## Free-tier behaviour to expect

| | |
|---|---|
| Render web service | Free, forever. 512 MB. Sleeps after 15 min idle, ~1 min to wake. |
| Supabase project | Free, never expires. 500 MB. **Pauses after 7 days of no activity**, data kept, resumes on the next request. |

The pause and the sleep both preserve data, so a family that uses the app regularly will
not notice either. Both wake up automatically on the next sync.

## Enrolling a family

The app has no enrolment screen yet — see the note in the changelog for the server. Once
a UI exists, enrolment is: set the server URL, enter a display name, then either create an
invite (share the code) or redeem one a family member created.
