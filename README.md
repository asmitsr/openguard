# OpenGuard

Free, open-source Android device tracking for your own phones: one account,
many devices, web dashboard, self-hostable. No paid subscription, no hidden
surveillance — tracking runs only on the owner's own devices, with visible
consent and a persistent on-device notification.

## Layout

- `backend/` — Cloudflare Worker (TypeScript + Hono) serving the API and
  the dashboard. Neon Postgres via `@neondatabase/serverless`.
- `backend/web/` — the dashboard (React, Vite; built to `web/dist/index.html`
  and inlined by the API — the old `backend/public/index.html` is no longer
  served).
- `android/` — Kotlin + Jetpack Compose app (MVVM).
- `db/schema.sql` — PostgreSQL schema for Neon.
- `docker-compose.yml` + `backend/src/server.ts` — self-host path: the same
  API on Node.js + plain Postgres in Docker (see "Self-host with Docker").
- `BUILD_NOTES.md` — what is done, what is stubbed, deploy steps, endpoint
  tests. Read it before deploying.

## MVP scope (Phase 0 + Phase 1)

Account register/login + session list/revoke, device register/unlink,
location upload with accuracy + timestamps, multi-device dashboard with
map, paginated history + CSV/JSON export, online/last-seen + battery
status, privacy dashboard, account deletion, audit events.

Not in MVP: Vault, ring/lock, remote erase, Lost Mode, SMS commands,
FMD/Traccar coupling, geofencing, advanced analytics.

## Deploy (short version)

1. Apply `db/schema.sql` in the Neon SQL Editor.
2. Build the dashboard: `cd backend/web && npm install && npm run build`
   (produces `web/dist/index.html`, which the API inlines — the Worker
   bundle step fails without it).
3. `cd backend && npx wrangler secret put DATABASE_URL`
4. `npx wrangler deploy`
5. Set the worker URL in `android/.../core/common/Constants.kt`
   (`API_BASE_URL`), push to GitHub — CI builds the debug APK.

Full steps + endpoint tests: `BUILD_NOTES.md`.

## Self-host with Docker

The same Hono backend also runs outside Cloudflare: on Node.js it talks to
plain Postgres through `postgres` (postgres.js) instead of the Neon HTTP
driver, behind the same `db()` interface — the API contract is identical.
The Worker entry never imports the Node driver, so the Workers build is
unaffected.

Prerequisites: Docker + Docker Compose.

1. `cp .env.example .env` and set a strong `POSTGRES_PASSWORD` (the
   password inside `DATABASE_URL` must match it).
2. `docker compose up -d --build` — starts Postgres and the API.
3. First run only: `docker compose exec api node scripts/migrate.mjs` —
   applies `db/schema.sql`, then `db/migrations/*.sql` in version order,
   recording applied versions in `schema_migrations`. Re-running is safe:
   applied files are skipped.
4. Open http://localhost:8787 — the dashboard is served by the API at `/`.

Notes:

- Migrations are Flyway-style versioned files run by a small Node script
  (`backend/scripts/migrate.mjs`) — Flyway itself is a JVM tool and this
  stack has no JVM.
- The stack listens on localhost only. To expose it, put a reverse proxy
  with HTTPS in front (e.g. Caddy or nginx); that is the operator's
  choice and not part of this compose file.
- `PORT` in `.env` is the host port; inside the container the API always
  listens on 8787.
- Data lives in the `openguard-db` Docker volume; `docker compose down`
  keeps it, `docker compose down -v` deletes it.

## License

Apache License 2.0 — see `LICENSE`. Docs are CC BY 4.0.
