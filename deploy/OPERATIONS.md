# Day-two operations

Deployment walkthrough lives in `deploy/README.md`. This file is what you read
when it is already live and something needs doing.

# The service, in one paragraph

Render holds the image and the container. Supabase holds the Postgres database.
State lives in exactly one place — Postgres — so a Render rebuild, restart,
sleep or rollback never loses data, and never needs a migration to be undone.
The only things on Render that matter are the two required database
environment variables, plus one optional override. Flyway runs on every boot, not
as a separate deploy step.

# Rotating `DATABASE_PASSWORD`

1. Supabase dashboard → the project → **Project Settings → Database** → reset the
   database password. Pick a new one.
2. Render dashboard → the service → **Environment**. Paste the new value into
   `DATABASE_PASSWORD` and save.
3. Saving an environment variable restarts the service with the new value. There
   is no rebuild and no image push involved.
4. `curl https://<your-render-url>/health`, then run the enrolment check from
   `deploy/README.md` step 5. Health alone does not prove the database works:
   `/health` never touches Postgres.

To rotate without downtime, reset the password and update Render within the same
session. While they disagree, the old value is rejected and the container fails
to connect; Flyway throws, `main` propagates, and the process exits non-zero.
There is no partial state — the server either authenticates or it does not.

Existing bearer tokens are unaffected. They are hashed and stored in
`member_tokens`; they are not derived from the database password.

# Redeploying

- **From code:** push to the tracked branch. `render.yaml` sets
  `autoDeployTrigger: commit`.
- **Without new code:** the **Manual Deploy** option on the service page, deploying
  the latest commit. Same code, fresh container, picks up whatever is in the
  environment.
- **After changing only an environment variable:** saving the variable already
  restarts the service. A manual deploy on top of that is harmless.

Only commits touching `server/**`, `.dockerignore` or `render.yaml` trigger a
build. `buildFilter` in `render.yaml` handles that. Android-only commits are
skipped, and Render always processes `render.yaml` itself regardless.

# Rolling back

- **Code:** open the deploy's entry in the service's deploy history and roll back
  to it. This redeploys a previously built image; it does not need a git revert.
- **Prefer a git revert** when you also want the repository to match what is
  running, otherwise the next push reintroduces the bug.

Rolling the image back does **not** roll the database back. Flyway migrations are
forward-only. If a bad migration is the problem, write a new migration that
undoes it and deploy again. Never edit a migration that has already been applied
— see below.

# Reading logs

- **Runtime logs:** the service page has a live tail. `logback.xml` in
  `server/src/main/resources/` logs to stdout at INFO, so Render captures
  everything the server prints.
- **Build logs:** the **Events** tab, per step. A failed Docker build fails
  before any container starts, so nothing appears in the runtime log at all —
  if the runtime log is empty, look at Events.
- **What a healthy boot looks like:** Flyway's migration lines, then Ktor's
  `Started`/`Responding at` lines. A boot that stops after nothing but the
  container start is a configuration failure, not a code failure.
- **Unauthenticated requests are not logged.** Only unhandled exceptions are, via
  the `StatusPages` block in `Application.kt`. A 401 leaves no trace, by design —
  a wrong token looks identical to no token and neither is an error worth
  logging. Do not read a quiet log as a broken logger.

# Diagnosing a failed boot

The process runs `migrate(dataSource)` before it binds a port
(`Application.kt:22`), so a bad database configuration means no container ever
serves traffic. Symptoms, in the order you are likely to hit them:

| Log text | Cause | Fix |
|---|---|---|
| `tenant/user postgres. not found` | the username is a bare `postgres` against the shared pooler | put `postgres.<project-ref>` in `DATABASE_URL`; it is read out of the URL and needs no separate variable |
| `DATABASE_PASSWORD is required` | `DATABASE_PASSWORD` unset or blank | set it; see rotation above |
| `password authentication failed for user` | `DATABASE_PASSWORD` empty, stale, or mistyped | set it; see rotation above |
| `FATAL: database … does not exist` | the path in `DATABASE_URL` is wrong | use the `/postgres` database |
| `pg_advisory_lock` errors, or migration hangs then times out | `DATABASE_URL` points at port 6543 | use the session pooler on 5432 |
| `no pg_hba.conf entry`, or a connection timeout | `DATABASE_SSL_MODE` is not `require`, or the host is unreachable over IPv4 | keep `require`; use the pooler, not the direct host |
| `relation "families" already exists` | pointing at a database that has some but not all of the family tables, so `migrate()` declined to adopt it and replayed V1 | repoint at an empty project, or make the schema complete. Adoption only happens when all 14 tables are already present. |

# Running Flyway by hand

Flyway is not a separate deploy step and there is no CLI wrapper in this
repository. It runs inside `migrate()` on every boot, so the first thing to try
is almost always just to fix the cause and redeploy: Flyway records what it
applied in `flyway_schema_history` and resumes from there. It is idempotent.

Check where it stopped — read-only, in the Supabase SQL editor:

```sql
select installed_rank, version, description, success
from flyway_schema_history
order by installed_rank;
```

A failed migration is recorded with `success = false`. `deploy/supabase/verify.sql`
has the surrounding checks.

If you have Docker locally, you can run the exact same Flyway code the container
runs, against the same database, without touching Render:

```bash
docker build -f server/Dockerfile -t buckwheat-sync:local .
docker run --rm \
  -e DATABASE_URL='postgresql://postgres.<project-ref>:…' \
  -e DATABASE_PASSWORD='…' \
  -e DATABASE_SSL_MODE=require \
  buckwheat-sync:local
```

It migrates on boot and then tries to bind `PORT`, which is unset locally so it
falls back to 8080. Ctrl-C once the migration output appears. Nothing in that
command touches the network except the database.

**Never edit an already-applied migration.** Flyway stores a checksum per version
and fails the next boot on a mismatch, which looks like corruption but is just a
mismatch. Add `V4__…` instead.

Do not run Flyway by hand from a second machine while Render is also deploying.
Two instances migrating the same database is exactly the situation Flyway's
advisory locks exist to prevent, and transaction pooling would break them anyway.

# When the free Supabase project pauses

A free project with too little database activity over 7 days is paused. Data is
retained. Unlike Render, this one does **not** wake itself.

- Supabase sends a warning email roughly a week before the pause, and a
  confirmation once it has happened.
- To resume: Supabase dashboard → the organisation → the paused project →
  **Resume project** → confirm. Then `curl` the health endpoint and the
  enrolment check.
- To keep it awake for good: make sure at least one device syncs every day. The
  app's background sync is enough; a family that opens the app regularly will
  never trip this.
- To keep it awake without opening the app, one `GET /health` a day is not
  enough on its own — `/health` does not query the database, and pausing is
  driven by database activity. A `POST /v1/family/create` per day would work but
  litters the database with junk families; do not do that.

# When the free Render service spins down

After 15 minutes with no inbound traffic the instance is stopped. The next
request wakes it in about a minute; Render shows a loading page to browsers in
that window. Nothing is lost and no action is needed.

Worth knowing: free instance hours are 750 per workspace per calendar month and
are shared across every free service. Keeping this one permanently awake by
pinging it would consume almost the entire allowance on its own. Do not add a
keep-warm ping.

# The one maintenance task

Every few days, check the Supabase dashboard. If it shows the project paused,
resume it. That is the only thing on this page that will ever surprise you.

# Changing the build image

`server/Dockerfile` pins `gradle:8.14.5-jdk17-noble` for the build stage and
`eclipse-temurin:17-jre` for the runtime stage. Both are current Docker Official
Images tags.

The previous build-stage pin, `gradle:8.14.3-jdk17`, still resolved but had been
dropped from the Official Images tag list for `library/gradle`, so it could have
vanished upstream without warning and taken every build with it. That is the
class of failure to expect here, and the symptom is immediate:
`manifest unknown` in the build log under Events.

If you ever need to move either tag:

- The build-stage image needs Gradle 8.x and JDK 17, to match
  `kotlin { jvmToolchain(17) }` in `server/build.gradle.kts`. Gradle's own
  documentation is at <https://docs.gradle.org/current/userguide/docker.html>.
- `server/` has no Gradle wrapper, so the Gradle version must come from the
  image. There is no way to pin it in the repository.
- The runtime image only needs a JRE. The app ships as a start script plus jars
  from `installDist`.
- Check a tag exists before committing: the Docker Hub tags page for the image,
  or `docker pull <tag>` locally.