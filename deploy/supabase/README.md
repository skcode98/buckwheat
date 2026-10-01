# The Supabase database

Everything here is plain hosted Postgres. This app uses Supabase for the
database and nothing else — no Auth, no Storage, no Edge Functions, no
PostgREST.

# Create the project

<https://supabase.com/dashboard> → **New project**. Pick a region near your
family and set a strong database password. That password is the one secret that
matters; it goes into Render as `DATABASE_PASSWORD`.

# Which connection string to use

The **Connect** dialog offers several. Only two are relevant.

## Use the shared pooler, session mode, port 5432

```
postgresql://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres
```

Session mode gives each client a dedicated Postgres connection for the life of the
session. That is what Flyway needs, and it is also IPv4-only, which matters
because Render's outbound networking is IPv4.

## Do not use the transaction pooler, port 6543

Same host, different port. Transaction mode shares one Postgres connection
between many clients, which means session-level state is not reliable. Flyway
takes advisory locks during migration, and advisory locks do not survive
transaction pooling. Migration fails, and it fails in a way that looks like a
migration bug rather than a connection-mode bug.

If you see an error mentioning `pg_advisory_lock`, or a migration that hangs and
then times out, you are on port 6543. Change the port.

## Do not use the direct connection on the free plan

```
postgresql://postgres:<password>@db.<project-ref>.supabase.co:5432/postgres
```

This one takes a bare `postgres` username, which is why it is tempting. But the
direct host is IPv6-only on the free plan, and Render connects over IPv4, so it
will hang or refuse. It becomes reachable if you enable the IPv4 add-on, which is
a paid feature.

# The username trap

Session mode identifies which project you are talking to from the **username**:

| Connection | Username |
|---|---|
| Shared pooler (session or transaction) | `postgres.<project-ref>` |
| Direct, and the dedicated PgBouncer pooler | `postgres` |

Sending a bare `postgres` to the shared pooler produces:

```
FATAL: (ENOTFOUND) tenant/user postgres. not found
```

That is a tenant lookup failure, not an authentication failure, so it does not
even get as far as checking the password.

`Config.kt` reads the username out of `DATABASE_URL` and uses it unless
`DATABASE_USER` is set, so pasting the pooler string verbatim is enough. The
password is stripped from the URL (Hikari supplies it). Set `DATABASE_USER` on
Render only to override that value — and if you do, it has to be
`postgres.<project-ref>`.

# Migrations

Nothing to run by hand. The server applies
`server/src/main/resources/db/migration/` on every boot via `Flyway.configure()`
in `DatabaseFactory.kt`:

| Version | File | Effect |
|---|---|---|
| 1 | `V1__initial_schema.sql` | 14 tables: `families`, `members`, `invites`, `member_tokens`, `budget_periods`, `transactions`, `archived_transactions`, `family_state`, `period_limits`, `saved_categories`, `saved_tags`, `recurring_templates`, `savings_goals`, `family_settings` |
| 2 | `V2__savings_goals_name.sql` | adds `savings_goals.name` |
| 3 | `V3__lock_down_public_access.sql` | enables row-level security on all 14 |

Flyway records what it applied in `flyway_schema_history` in the `public` schema.

One setting is worth knowing about. `migrate()` calls
`.baselineOnMigrate(true).baselineVersion("0")`. That is a safety net for a
database that already has tables. It is a footgun if you ever repoint the
service at a different database that happens to have its own tables in `public`:
Flyway will baseline at 0, then try `create table families`, and fail because the
table is already there. If you change `DATABASE_URL`, point it at an empty
project.

# Row-level security, honestly

Migration `V3` enables RLS on all 14 tables and creates **no policies**. Here is
what that actually means, without overselling it:

- Supabase exposes the `public` schema through an auto-generated REST API, and
  the keys for it are the `anon` and `authenticated` roles. With RLS enabled and
  no policies, those roles match no rows, so anything holding only the anon key
  reads nothing. That is the attack this stops.
- **The sync server is not protected by that.** It connects as `postgres`, which
  is a superuser-style role that bypasses RLS outright. Every read and write it
  does is unaffected by any policy you might add. RLS governs the Supabase
  dashboard and REST surface; it does not govern the server's own connection.
- RLS is not a substitute for the bearer token. Authorisation on this service is
  done in code: `TokenService.verify` hashes the presented token with SHA-256 and
  looks it up in `member_tokens`, and `SyncStore` scopes every query to the
  caller's `familyId`. That is what stops one family reading another's data.

## Never put the anon key in the app

The Android app talks only to this service, over its own `/v1` endpoints, with
its own bearer tokens. It never talks to Supabase. Do not add the Supabase anon
key, the service-role key, or a Supabase URL to the app or to Render. There is no
code path that needs either, and shipping them would hand out a way to bypass
the authorisation the server does.

# Verifying

Paste `verify.sql` from this folder into the Supabase **SQL editor**. It is
read-only.