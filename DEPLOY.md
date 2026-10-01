# Family sync — deployment

The full walkthrough lives in **[deploy/README.md](deploy/README.md)**. Start
there.

Short version: the sync service is a separate HTTP service in `server/`, hosted
on Render's free web service and backed by a free Supabase Postgres project.
Both tiers are $0, neither needs a credit card, and the deployment material all
lives under [`deploy/`](deploy/README.md).

- First-time setup, Supabase project creation, blueprint launch, `curl`
  verification: **[deploy/README.md](deploy/README.md)**
- Every environment variable the server reads: **[deploy/env.example](deploy/env.example)**
- Day two — rotating the database password, redeploys, rollbacks, logs, the free
  Supabase pause: **[deploy/OPERATIONS.md](deploy/OPERATIONS.md)**
- Database setup and the row-level-security story:
  **[deploy/supabase/README.md](deploy/supabase/README.md)**

Two files outside `deploy/` carry the deployment itself:
[`render.yaml`](render.yaml) is the Render blueprint, and
[`server/Dockerfile`](server/Dockerfile) builds the image.

## Why a separate service, and why Docker

Yes, it is fine — and it is already the architecture, not a workaround. The
Android app is an HTTP client of `/v1/sync` and `/v1/family/*` and hard-codes
nothing about where the service lives: the user types the base URL into the
Family Sync sheet. Keeping the deployment files in this same project directory
is exactly what `deploy/` does.

Docker is required for one specific reason. Render's native build runtimes always
build at the repository root, and the root `build.gradle.kts` applies the Android
and KSP plugins — so a native Render build would try to compile the Android app
and fail without an Android SDK. `server/Dockerfile` copies only `server/`, which
has its own Gradle build, and sidesteps that.

## Free-tier behaviour to expect

| | |
|---|---|
| Render web service | Free, forever. 512 MB. **Sleeps after 15 min idle**, ~1 min to wake. |
| Supabase project | Free, never expires. 500 MB. **Pauses after 7 days of low activity**, data kept. |

Both preserve data, so a family that uses the app regularly will not notice
either. The difference is in how they recover: a Render sleep fixes itself on the
next request, but a paused Supabase project needs a manual **Resume project**
click in the dashboard. See [OPERATIONS.md](deploy/OPERATIONS.md).

## Enrolling a family

Open Settings and pick "Family sync". To start a family, enter the server URL
from Render, type a display name, then tap create; the app shows the family and
member ids. To join one, paste an invite code and a display name, then tap join.
Once enrolled, the sheet can mint a fresh invite code to read out or share.
Signing out stops background sync and forgets the family on this device.