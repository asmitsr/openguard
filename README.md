# OpenGuard

Free, open-source Android device tracking for your own phones: one account,
many devices, web dashboard, self-hostable. No paid subscription, no hidden
surveillance — tracking runs only on the owner's own devices, with visible
consent and a persistent on-device notification.

## Layout

- `backend/` — Cloudflare Worker (TypeScript + Hono) serving the API and
  the dashboard. Neon Postgres via `@neondatabase/serverless`.
- `backend/public/index.html` — the dashboard (single file, MapLibre + OSM).
- `android/` — Kotlin + Jetpack Compose app (MVVM).
- `db/schema.sql` — PostgreSQL schema for Neon.
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
2. `cd backend && npx wrangler secret put DATABASE_URL`
3. `npx wrangler deploy`
4. Set the worker URL in `android/.../core/common/Constants.kt`
   (`API_BASE_URL`), push to GitHub — CI builds the debug APK.

Full steps + endpoint tests: `BUILD_NOTES.md`.

## License

Apache License 2.0 — see `LICENSE`. Docs are CC BY 4.0.
