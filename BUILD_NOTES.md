# OpenGuard — Build Notes (MVP, Phase 0 + Phase 1)

Built 16 Nov 2026. Backend + dashboard verified (typecheck green, local
`wrangler dev` smoke test passed, docs audit passed). Android written but
never compiled (no SDK/Java on this VM) — first real build is GitHub Actions.

## Professionalization round 1 — React dashboard + Docker self-host (built 17 Nov 2026, not yet deployed)

First step of the gradual professionalization the user asked for (17 Nov
00:10): keep the TypeScript/Hono backend on Workers (fast, always-on, free)
and close the blueprint gaps that add real quality — a React + TypeScript
dashboard and a Docker Compose self-host path. No API contract change: no
endpoint, path, method, field, or status code changed; the Android app is
untouched and keeps working. Hilt (Android DI) is a later round, not this one.

### Deploy steps (parent) — in this order

1. **Build the dashboard FIRST:** `cd backend/web && npm install`
   (once) `&& npm run build` — produces `backend/web/dist/index.html`
   (single self-contained file, 248,780 bytes). The Worker bundle step
   FAILS without it: `src/index.ts` now imports `../web/dist/index.html`.
2. **Deploy the Worker** with the existing esbuild bundle step
   (`./node_modules/.bin/esbuild src/index.ts --bundle --format=esm
   --platform=browser --target=es2022 --loader:.html=text
   --outfile=dist/index.js`, then the cf.py upload as before). Bundle is
   now 540,634 bytes (was 333,820) — the React dashboard replaced the
   ~35 KB vanilla file. Cloudflare's Worker size limit is 64 MiB
   uncompressed on Free and Paid alike (verified against the official
   limits page 17 Nov 2026; the old 3 MB compressed Free limit was
   removed in Sept 2026), so the bundle fits with huge headroom.
3. **Push to GitHub** as before. `backend/web/dist/` and `backend/dist/`
   are now gitignored (build outputs — a clean checkout rebuilds them);
   do NOT include them in the push zip.
4. **No Neon migration** in this round (no schema change). Smoke-test the
   live dashboard after deploy (login + one device list + map render).

### What was built — React dashboard (`backend/web/`)

- Vite + React 19 + TypeScript, exact-pinned deps, `build` =
  `tsc --noEmit && vite build`. No state library, no router, no
  component library — plain React + fetch.
- `vite-plugin-singlefile` inlines all local JS + CSS into one
  `dist/index.html` (verified: dist contains only index.html; the only
  external `<script src>` / `<link href>` are the MapLibre unpkg CDN
  tags, same as the old dashboard). The existing esbuild
  `--loader:.html=text` deploy flow works unchanged.
- Full feature parity with the old vanilla dashboard, verified by the
  coordinator against the original file: auth (register/login/logout),
  forgot-password recovery, devices list/add/unlink, device sign-out,
  MapLibre map (OSM raster, markers, accuracy circle, history path),
  location history + load-more + CSV/JSON export, Command Center
  (ring/lock/Lost Mode on+off, erase with step-up + type-ERASE +
  offline-disabled button), command list + verify panel, alerts,
  recovery codes (step-up generate, shown once, remaining count),
  sessions list/revoke + revoke-others, audit log, privacy view,
  account delete. Two-click confirms, 10s arm timers, 5-min
  online/offline rule, and loading/empty states all match.
- Auth contract reproduced exactly: client-side PBKDF2-SHA256 600k via
  WebCrypto, same localStorage keys (`openguard_access`,
  `openguard_refresh`, `openguard_email`), same `/api/v1` endpoints,
  401 → refresh → single retry. CSS is the old dashboard's stylesheet
  verbatim except `#token-box` lost its `display:none` (React renders it
  conditionally instead).
- Worker change (minimal): `src/index.ts` imports
  `../web/dist/index.html` instead of `../public/index.html`.
  `src/html.d.ts` needed no change (wildcard `*.html`).
  `backend/public/index.html` is now UNUSED — kept in place, not deleted.

### What was built — Docker self-host

- Same Hono app on Node via `backend/src/server.ts`
  (`@hono/node-server`, port 8787 default, graceful SIGINT/SIGTERM
  shutdown). The Worker entry never imports it.
- **Driver switch (the key piece):** `@neondatabase/serverless` speaks
  SQL-over-HTTP to Neon and cannot talk to a plain Postgres container,
  so the Node path uses `postgres` (postgres.js) behind the same `db()`
  interface: `src/lib/dbNode.ts` (imported ONLY by `server.ts`) installs
  a postgres.js-backed `sql` via `setSqlImpl` in `src/lib/db.ts`. The
  Worker bundle contains zero postgres.js markers (verified by grep:
  `PostgresError`, `dbNode`, `node-server`, `setSqlImpl` all absent;
  `setSqlImpl` is tree-shaken out). Route code is untouched.
- The adapter normalizes three real Neon-vs-postgres.js differences so
  route behaviour matches exactly: int8 (bigserial ids) parsed as JS
  numbers (postgres.js returns strings), timestamps kept as raw ISO
  strings (postgres.js returns Date objects — would have broken the CSV
  export and the locations cursor), and `PostgresError` translated to
  `NeonDbError` (with field-name mapping) so the 23505 catches in
  `auth.ts` (register race → 409) and `devices.ts` (register race →
  re-link) still fire. The three non-template bulk inserts (recovery
  codes, locations, security events) map to `sql.unsafe` with `$1..$n`
  params. No route uses `sql.transaction` / `.array` / `.values`.
- `docker-compose.yml` (repo root): `api` + `db`
  (`postgres:16-alpine`), named volume `openguard-db`, `pg_isready`
  healthcheck with `depends_on: service_healthy`, no published DB
  ports, localhost-only binding (`127.0.0.1:${PORT:-8787}:8787`).
  `backend/Dockerfile` (multi-stage, `node:22-alpine`): builds the
  React dashboard first (required — `index.ts` inlines `web/dist/`),
  then `tsc` + `npm run build:node` (esbuild bundles `server.ts` to
  `dist-node/server.js`; plain tsc cannot emit the `.html` import).
  Final stage runs as `USER node`, production deps only
  (`migrate.mjs` needs `postgres` at runtime), healthcheck on
  `/api/v1/health`. `.env.example` has placeholders only
  (`POSTGRES_PASSWORD`, `DATABASE_URL`, `PORT`).
- **Migrations:** Flyway-style versioned files run by a small Node
  script, `backend/scripts/migrate.mjs` — Flyway itself is a JVM tool
  and this stack has no JVM. Applies `db/schema.sql` first, then
  `db/migrations/*.sql` in filename order, one transaction per file
  (migration + `schema_migrations` row together), skips applied
  versions, non-zero exit on error. `.simple()` protocol allows the
  multi-statement files including the `DO $$` block in 003.
- Self-host run steps (also in README.md):
  `cp .env.example .env` (set a strong `POSTGRES_PASSWORD`, same
  password inside `DATABASE_URL`) → `docker compose up -d --build` →
  `docker compose exec api node scripts/migrate.mjs` (first run only;
  safe to re-run) → open http://localhost:8787. The stack listens
  on localhost only; an HTTPS reverse proxy is the operator's choice,
  not part of the compose file. `docker compose down -v` deletes the
  data volume.

### Verification (coordinator, independent of the workers)

- `npm run build` (tsc) green in `backend/` AND `backend/web/`; Vite
  build reproduces `dist/index.html` (248,780 bytes).
- Worker bundle builds via the exact esbuild command: 540,634 bytes
  (gzip 151,216); dashboard confirmed inlined; 0 postgres.js markers.
- `npm run build:node` builds `dist-node/server.js` (770,891 bytes).
- `docker-compose.yml` parses as YAML (services, healthcheck
  dependency, ports, volumes confirmed). `node --check` clean on
  `migrate.mjs`. Secrets grep over all new/changed files: clean.
- Route files, `android/`, `db/schema.sql`, all migrations, and
  `backend/dist/` untouched (verified by mtime).
- **NOT verified here (no Docker daemon, no live Postgres on this
  VM):** `docker compose up` end-to-end; migrations against a real
  server (esp. the 003 `DO` block inside a transaction); real query
  round-trips (prepared-statement type inference, `ANY(array)`, the
  23505 translation path, int8/timestamp shapes against a real
  server). The PostgresError→NeonDbError translation is code-reviewed
  only. The Node server itself was smoke-tested live by the worker
  (health 200, dashboard served, 404 on unknown path, 500 on DB
  endpoint with no DB) — but that exercised the connection-error path,
  not a server-error path.

### Gaps / stubbed

- Nothing stubbed in either workstream. Hilt (Android DI) and the
  remaining phases (Vault, geofencing, analytics, public API) are
  later rounds. `backend/public/index.html` is dead code kept in
  place — delete it in a later cleanup push once the React dashboard
  is proven live.

## Map + offline round (built 17 Nov 2026, not yet deployed)

Fixes the user asked for after testing the Phase 2 APK: he could not
find the phone on the map, the same phone appeared twice in the device
list, and he wanted the phone to reconnect to WiFi when it goes offline.

### Deploy steps (parent) — in this order

1. **Apply the migration to Neon.** Paste `db/migrations/004_dedup.sql`
   into the Neon SQL Editor and run it (idempotent). Adds
   `devices.hardware_id` + a partial unique index on
   `(user_id, hardware_id)` for non-NULL values. Do NOT re-apply
   `db/schema.sql`, `002_commands.sql` or `003_recovery.sql`.
2. **Deploy the Worker** (backend changed: device register re-link).
3. **Push to GitHub** — CI builds the Android APK as before.

### What was built

- **Map discoverability (Android):** each device row now has an explicit
  map-pin button (opens the map for that device) next to the shield icon,
  plus a one-line hint above the list ("Tap a device — or its map pin —
  to see it on the map"). Tapping the row still opens the map.
- **Map reliability (Android):** the last known location marker is bigger
  (12dp) with a translucent halo so it stands out; the map screen shows
  "Last seen … · ±N m" under the map; the empty state now says "No
  locations yet — turn tracking on and wait for the first fix"; API
  errors get a Retry button; and a style/tiles load failure (MapLibre
  `OnDidFailLoadingMapListener`) shows an error + Retry that recreates
  the MapView.
- **Relative time everywhere:** new shared `core/common/RelativeTime.kt`
  (`relativeTime()`, same thresholds as the dashboard's `relTime()`).
  "Last seen" is now relative in the device list, on the map screen, and
  in history (which previously showed an absolute date). The dashboard
  already used `relTime()` for last-seen and `toLocaleString()` for
  history — no dashboard change needed.
- **Duplicate devices (Android + backend):** reinstalling the app used to
  register the same phone as a new device. The app now sends its
  ANDROID_ID as `hardware_id` on register; the server re-links instead
  of duplicating — if a device with the same `(user_id, hardware_id)`
  exists, it returns the EXISTING row (history preserved) with a FRESH
  device token (HTTP 200, audit event `device_relink`). New devices
  still get 201. A concurrent-register race (23505) falls back to the
  same re-link. The one-time-token-shown-once behaviour is unchanged,
  and `hardware_id` is never returned by the API (it stays server-side).
  Note: rows registered before migration 004 have NULL `hardware_id`,
  so an EXISTING duplicate is not auto-merged — unlink the extra row
  once from the app or dashboard; reinstalls after that re-link.
- **Offline → WiFi reconnect (Android, the feasible maximum):** new
  `core/location/WifiReconnect.kt` + a Settings "WiFi reconnect" section.
  The owner enters a network (SSID + password, or open); the app
  suggests it via WifiNetworkSuggestion (API 29+), Android asks ONCE
  for approval, and the OS then auto-connects when the network is in
  range. The password is handed to Android only and never stored. The
  Settings screen lists the app's suggested networks (read-back needs
  API 30+) and can remove them, and opens the system WiFi panel in one
  tap. When connectivity is lost with NO network left (LocationService
  NetworkCallback `onLost`, guarded by an `activeNetwork == null` check
  so WiFi→cellular handovers do not count), the app records one
  `sync_gap` event per hour (the server's event CHECK has no offline
  type — the reason is in the details) and, if WiFi is off, posts a
  high-priority notification with a one-tap "Turn on WiFi" action that
  opens the WiFi panel. No "back online" event: the resumed heartbeat
  already shows it, and `onAvailable` fires on every handover.
- **Offline outbox (verified, no change needed):** locations are written
  to the Room outbox and deleted only after the server confirms; the
  existing `onAvailable` callback already enqueues a one-time worker,
  and UploadWorker uploads locations first — so queued locations flush
  on reconnect. Confirmed by reading the code; no edit was required.
- **New permissions (both normal, no runtime prompt):**
  `ACCESS_WIFI_STATE` (read whether WiFi is on) and `CHANGE_WIFI_STATE`
  (add/remove network suggestions). No location-permission changes.

### Honest WiFi limits (also shown in the app)

- The app CANNOT turn WiFi on, force a connection, or read the user's
  saved networks: `setWifiEnabled()` is a no-op for apps targeting
  Android 10+, and `getConfiguredNetworks()` returns nothing useful to
  a third-party app. That is why the owner enters networks by hand and
  why the offline notification opens the WiFi panel for a one-tap
  manual toggle.
- Suggestions need a one-time user approval (Android shows the prompt);
  if the owner denies it, suggestions are ignored until re-enabled in
  system settings. After approval, the OS — not the app — decides when
  to connect (in range, no better network).
- Suggestions need Android 10+; listing them needs Android 11+.
  Below Android 10 the Settings section explains it is unsupported.
- A suggestion does not help while WiFi is off — that is what the
  offline notification is for.

### Test the changed endpoint (BASE = deployed worker URL)

```sh
# register a device WITH a hardware id (ACCESS = user access token)
curl -s -X POST $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Pixel test","model":"Pixel 8","os_version":"Android 14","hardware_id":"0123456789abcdef"}'
# → 201, {device, device_token} — save DEVICE_ID + DEVICE_TOKEN
# register AGAIN with the same hardware_id (simulates a reinstall)
curl -s -X POST $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Pixel test 2","model":"Pixel 8","os_version":"Android 14","hardware_id":"0123456789abcdef"}'
# → 200, SAME device id, NEW device_token; the old token now gets 401
# (list devices to confirm there is still only one row)
curl -s $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS"
# register without hardware_id still works (old clients): → 201, new row
curl -s -X POST $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d '{"name":"No HWID"}'
```

### Map + offline stubbed / gaps

- Existing duplicate rows (NULL `hardware_id`) are not auto-merged;
  the owner unlinks the extra one once (see above).
- The map still uses OSM raster tiles directly; tile load failures show
  the retry state but there is no offline tile cache (Phase 5).
- WiFi suggestions cover networks the owner enters by hand only — the
  app cannot see already-saved networks on Android 10+ (see limits).

## Phase 3 Remote Recovery (built 16 Nov 2026, not yet deployed)

Remote data erase (with step-up auth + on-device abort), remote sign-out,
recovery codes, offline command queue (24h), command verification, plus an
urgent fix for the Gson null-payload crash that broke Phase 2 ring/lock.

### Deploy steps (parent) — in this order

1. **Apply the migration to Neon.** Paste `db/migrations/003_recovery.sql`
   into the Neon SQL Editor and run it (idempotent: `IF NOT EXISTS` +
   a DO block). Adds `recovery_codes` + `step_up_tokens` tables and extends
   the `commands` action CHECK to include `'erase'` (replaces the
   auto-named `commands_action_check` with `commands_action_check_v2`).
   Do NOT re-apply `db/schema.sql` or `002_commands.sql` — v1 + v2 live.
2. **Deploy the Worker** (backend changed: step-up, recovery, revoke,
   sign-out, erase, verify endpoints; dashboard additions).
3. **Push to GitHub** — CI builds the Android APK as before.

### URGENT FIX folded in: Gson null-payload crash (Phase 2 bug)

The user tested the Phase 2 APK: ring/lock/Lost Mode did nothing, and the
Command Center showed "Expected a com.google.gson.JsonObject but was
com.google.gson.JsonNull; at path $.payload". Root cause: the server
correctly returns `payload: null` for ring/lock (only lost_mode_on carries
a payload), but Gson THROWS when deserializing an explicit JSON null into
a `JsonObject`-typed field even when the Kotlin type is nullable — so
every create-command response, history list, and worker poll for a
ring/lock command crashed, the one-time worker was never enqueued, and
the command expired unexecuted. Fix: `CommandDto.payload`,
`SecurityEventRequest.details` and `SecurityEventDto.details` changed
from `JsonObject?` to `JsonElement?` (Gson's JsonElement adapter tolerates
null); the only payload reader (CommandExecutor, lost_mode_on message)
now null-checks AND JsonNull-checks (`takeIf { it.isJsonObject }` before
`.get`); CommandCenterViewModel maps parser-exception text to one friendly
line ("Could not read the server response — please try again") instead of
showing the raw Gson error. Server contract unchanged (`payload: null`
stays null). This fix is what makes Phase 2's ring/lock actually work.

### Erase safety chain (read before testing erase)

Erase is the only destructive command, so it has six gates — no single
mistake can wipe a phone:
1. **Step-up auth**: creating an erase requires a fresh single-use 10-min
   step-up token from `POST /auth/step-up` (password re-verified via the
   client-derived key, same as account deletion).
2. **Type-to-confirm**: dashboard + app both require typing exactly `ERASE`
   on a warning screen that lists what is wiped.
3. **Rate limit**: 1 erase/hour/user (in-memory, per-isolate — same caveat
   as all existing limits).
4. **Online-only**: the server refuses erase for a device not seen in the
   last 5 minutes (409) — an erase must never sit queued for a phone that
   may never come back. Non-erase commands queue for 24h instead.
5. **Device admin required**: the device acks `failed` ("Device admin is
   not enabled — erase needs it") when admin is inactive.
6. **60-second on-device grace delay**: the phone shows a loud, visible
   countdown over the lock screen (`EraseCountdownActivity`); the owner
   aborts by confirming the device credential (PIN/pattern/biometrics),
   which clears the state and acks the command as failed ("Erase aborted
   by owner"). Only at zero does `DevicePolicyManager.wipeData(
   WIPE_EXTERNAL_STORAGE)` run. A WorkManager backstop (60s delay) wipes
   if the activity/process died mid-countdown; the countdown is recomputed
   from a stored absolute deadline, so rotation or process death cannot
   stretch it.

Honest limits: wipeData needs device admin — without it erase cannot run
(the UI says so plainly). Factory Reset Protection is deliberately NOT
cleared (no `WIPE_RESET_PROTECTION_DATA`): a wiped stolen phone stays
locked to the owner's Google account. If the phone has NO screen lock,
there is no credential confirm, so abort is unavailable from that screen
(the 60s delay + visible screen + the other five gates remain). The
WorkManager backstop delay is a minimum, never exact — under Doze it can
fire late, but the activity is the primary path. An erase command acked
as "countdown started" and then aborted shows status `failed` with the
abort result (the ack endpoint updates the row).

### What was built

- **Backend** (no new dependencies):
  - `POST /auth/step-up` — re-verifies the derived key, returns a
    single-use 10-min token (hash stored in `step_up_tokens`, `no-store`,
    auth limiter, audit `step_up`). Shared `verifyStepUpToken` helper in
    `lib/auth.ts` (atomic DELETE … RETURNING, so a token works once).
  - `POST /auth/recovery-codes/generate` — step-up token required;
    generates 10 codes (`XXXX-XXXX-XXXX`, 32-char alphabet, no
    confusables), deletes old codes first, stores only SHA-256 hashes,
    returns `{codes}` once (`no-store`, audit `recovery_codes_generate`).
  - `GET /auth/recovery-codes` — returns only `{remaining}`.
  - `POST /auth/recover` — no auth; atomically claims one unused code
    (UPDATE … WHERE used=false RETURNING), then sets the new password
    (client-side KDF salt + derived key, same as register), deletes all
    sessions, audit `password_recover`, 204. Unknown email and bad code
    both give 401 `invalid email or recovery code` (no enumeration).
  - `POST /auth/sessions/revoke-others` — deletes all sessions except the
    current one, returns `{revoked}`, audit `sessions_revoke_others`.
  - `POST /devices/:id/sign-out` — rotates the device token to a hash of
    a discarded random token (device is signed out; re-pair = unlink +
    re-add), deletes the user's other sessions, audit `device_sign_out`,
    204. Device row + history survive.
  - `POST /devices/:id/commands` — new `erase` action with the guards
    above (step-up, 1/hour, online-only, 5-min expiry); all non-erase
    actions now expire in 24h (was 5 min). Idempotency lookup runs before
    the erase guards, so replaying a key doesn't burn a step-up token.
  - `GET /commands/:id` — one command + its audit timeline (oldest first);
    nonce returned truncated (first 8 chars + …). The `command_ack` audit
    detail now includes `command_id` so the timeline is complete.
  - Privacy `server_data` lists recovery codes (hashes only).
- **Dashboard** (`backend/public/index.html`): online/offline indicator
  per device (online = seen < 5 min) + 24h-queue note; Erase button
  (danger, disabled offline) with inline warning + type-ERASE + password
  → step-up → erase; clickable command rows → verify panel (issued,
  expires, nonce, status, result, audit timeline); Recovery codes card
  (remaining count, password-confirmed generate, codes shown once with
  copy + "I have saved these codes" checkbox gating Close); "Sign out
  all other sessions" (two-click confirm); device "Sign out" link
  (two-click confirm); "Forgot password? Recover with a recovery code"
  form on the auth view. No `confirm()` in any new flow.
- **Android** (47 .kt files, zero new dependencies, no new permissions):
  - API layer: all 7 new endpoints + DTOs (paths/methods/fields match
    the backend contract exactly); `CreateCommandRequest` gains
    `step_up_token`; `CommandDto` gains `nonce_truncated`.
  - Erase: `DeviceAdminManager.wipeData()`, `EraseCountdown`
    (state + notification + backstop enqueue + abort with ack),
    `EraseWorker` (backstop wiper), `EraseCountdownActivity`
    (over-lock-screen countdown, credential-confirmed abort, failed-wipe
    screen when admin was revoked mid-countdown), `EraseStore` (pending
    erase + absolute deadline, StateFlow), `MainActivity` surfaces the
    countdown on launch (erase takes precedence over Lost Mode).
    Device-admin strings + Settings text now disclose erase.
  - `CommandExecutor`: `erase` action + ack wording — executed results
    prefixed `Verified — `, replays `Duplicate — already executed, replay
    ignored`, unknown actions `Failed verification — unknown action: …`.
  - Command Center: online/offline line, danger-zone Erase section
    (type ERASE + password → step-up → send; disabled offline or without
    admin), clickable rows → verify dialog, `erase` rows with alert icon.
  - Recovery: `RecoveryScreen` + `RecoveryViewModel` (Settings →
    remaining count, generate, show-once box with copy-all + saved
    checkbox), `RecoverAccountScreen` (Login → email + code + new
    password → "Password changed — log in with your new password").
  - Settings: "Recovery codes" row, "Sign out all other sessions"
    (confirm dialog → "N other sessions signed out"), "Sign out this
    phone" (confirm dialog; stops tracking, cancels periodic work).
  - `LocationService` registers a `ConnectivityManager.NetworkCallback`;
    onAvailable enqueues a one-time worker so commands are fetched as
    soon as connectivity returns (only while tracking runs; no manifest
    CONNECTIVITY_ACTION receiver — it doesn't fire on Android 7+).
  - AuthRepository: `stepUp`, `generateRecoveryCodes`,
    `recoveryCodesRemaining`, `recover`, `revokeOtherSessions`;
    DeviceRepository: `signOutDevice` (clears local device state when
    it is this phone).

### Test the new endpoints (BASE = deployed worker URL)

Derive a key for curl (Node, same KDF as the dashboard):
`node -e "const c=require('crypto');console.log(c.pbkdf2Sync(process.argv[1],Buffer.from(process.argv[2],'hex'),600000,32,'sha256').toString('hex'))" '<password>' '<salt-hex>'` — get the salt first via `curl -s "$BASE/api/v1/auth/salt?email=$EMAIL"`.

```sh
# step-up (DERIVED_KEY = hex PBKDF2-SHA256 600k of the password with the user's salt)
curl -s -X POST $BASE/api/v1/auth/step-up \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"derived_key\":\"$DERIVED_KEY\"}"
# → {"step_up_token":"...","expires_in":600}  (wrong password → 403 'incorrect password')
# save it: STEP_UP_TOKEN=<from above>

# generate recovery codes (shown once; old codes invalidated)
curl -s -X POST $BASE/api/v1/auth/recovery-codes/generate \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"step_up_token\":\"$STEP_UP_TOKEN\"}"
# → {"codes":["XXXX-XXXX-XXXX", ...]}  (reusing the same token → 403)

# remaining codes (never returns the codes themselves)
curl -s $BASE/api/v1/auth/recovery-codes -H "Authorization: Bearer $ACCESS"
# → {"remaining":10}

# revoke all other sessions
curl -s -X POST $BASE/api/v1/auth/sessions/revoke-others \
  -H "Authorization: Bearer $ACCESS"
# → {"revoked":2}

# erase (needs a FRESH step-up token — each token is single-use — and an online device)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/commands \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"action\":\"erase\",\"step_up_token\":\"$STEP_UP_TOKEN\"}"
# → 201 command with expires_at ~5 min out; no/bad token → 403; 2nd erase within 1h → 429; offline device → 409

# non-erase command now queues 24h (check expires_at)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/commands \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"action":"ring"}'

# command verify (COMMAND_ID from any create response)
curl -s $BASE/api/v1/commands/$COMMAND_ID -H "Authorization: Bearer $ACCESS"
# → {"command":{...,"nonce_truncated":"abcd1234…",...},"audit":[{"event":"command_create",...}]}

# device sign-out (kills the device token + other sessions; keeps the row + history)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/sign-out \
  -H "Authorization: Bearer $ACCESS" -i
# → 204; afterwards the old device token gets 401 on /heartbeat and /commands/pending

# recover with a recovery code (no auth; NEW_SALT = 32 fresh random hex chars,
# NEW_DERIVED_KEY = hex PBKDF2-SHA256 600k of the new password with NEW_SALT)
curl -s -X POST $BASE/api/v1/auth/recover -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"code\":\"XXXX-XXXX-XXXX\",\"salt\":\"$NEW_SALT\",\"derived_key\":\"$NEW_DERIVED_KEY\"}" -i
# → 204; wrong code or unknown email → 401 'invalid email or recovery code'; reusing the code → 401
# remaining count drops by 1 afterwards; all sessions are dead — log in again with the new password
```

### Phase 3 stubbed / gaps

- **Erase rate limit is per-isolate** (in-memory limiter) — a burst spread
  across isolates can exceed 1/hour. Same caveat as all existing limits.
- **Sign-out re-pair needs unlink + re-add** (the rotated token is
  discarded, so the device cannot re-pair in place; history is lost with
  the unlink). A pair-with-token flow would fix this.
- **No-screen-lock abort unavailable** (see erase limits above).
- **Recovery codes never expire**; used codes stay in the table (marked
  used). Expired step-up tokens are never purged (rows are tiny; a purge
  job would clean them).
- **No Android UI lists security events yet** (still dashboard-only;
  carried over from Phase 2).
- Android still never compiled on this VM — CI is the first real build.

## Phase 2 Protection (built 16 Nov 2026, not yet deployed)

Remote commands (ring, lock, Lost Mode), SIM-change alerts, device status
monitoring, security event log, Command Center (Android + dashboard).

### Deploy steps (parent) — in this order

1. **Apply the migration to Neon.** Paste `db/migrations/002_commands.sql`
   into the Neon SQL Editor and run it (idempotent: `IF NOT EXISTS`
   throughout). Adds `commands` + `security_events` tables and 4 columns
   on `devices` (`lost_mode`, `lost_message`, `location_permission`,
   `location_enabled`). Do NOT re-apply `db/schema.sql` — v1 is live.
2. **Deploy the Worker** (backend changed: new `routes/commands.ts`,
   heartbeat + device-list extensions, dashboard additions).
3. **Push to GitHub** — CI builds the Android APK as before.

### Polling latency (honest)

There is NO push channel in this phase (no FCM): the phone polls pending
commands in `UploadWorker`, which runs at most every 15 minutes
(WorkManager minimum). A command sent from the dashboard therefore reaches
the phone within ~15 minutes in the worst case — sooner if the phone's
periodic work runs earlier, and within seconds when the owner sends the
command from the Command Center on the same phone (it enqueues a one-time
worker). Commands expired after 5 minutes in Phase 2; Phase 3 extended
non-erase commands to 24h so an offline phone no longer loses them (erase
still expires in 5 minutes and refuses offline devices). Push is never
command authentication: the phone
fetches over authenticated TLS (device token) and executes only commands
bound to its own device id, with id+nonce replay protection.

### What was built

- **Backend** (`backend/src/routes/commands.ts`, 6 new endpoints, all
  following the v1 patterns): `POST /devices/:id/commands` (create;
  owner-only; actions ring/lock/lost_mode_on/lost_mode_off; server nonce;
  5-min expiry; idempotency key; 20/min/user rate limit; audit
  `command_create`; lost-mode commands update the device row),
  `GET /devices/:id/commands` (history, newest first),
  `GET /devices/:id/commands/pending` (device token; expires stale rows,
  marks returned rows `delivered`),
  `POST /commands/:id/ack` (device token; command must belong to the
  device, else 404 + `command_reject` audit; success audits `command_ack`),
  `POST /devices/:id/security-events` (device token; bulk insert 1–100),
  `GET /devices/:id/security-events` (user, newest first).
  Heartbeat accepts optional `location_permission` + `location_enabled`;
  device list returns the 4 new columns.
- **Dashboard** (`backend/public/index.html`): "LOST MODE" badge on device
  cards; battery/network/location-permission/location-enabled in device
  details; Command Center card (Ring / Lock / Lost Mode ON / OFF +
  200-char message input + command-status list + honest ~15-min note);
  Alerts card (security events with Confirmed/Indication badge; SIM
  change reads "SIM changed — check if this was you").
- **Android** (41 .kt files, zero new dependencies):
  - `CommandExecutor` — validates, replay-guards (id+nonce in
    SecurityPrefs), dispatches, returns ack outcome.
  - `RingService` — alarm-stream loop at max volume (restored on stop) +
    vibration + Stop notification; stops on unlock / Stop tap / 2 min;
    rings in silent/DND. Caveat: Android 12+ blocks background FGS starts,
    so a ring command acks `failed` ("open the app or turn tracking on
    first") unless tracking is on or the app is open.
  - `DeviceAdmin` — `DeviceAdminReceiver` + `lockNow()`; admin activation
    flow in Settings; `onDisabled` records `admin_disabled`. No password
    policy (dead since Android 9–11).
  - Lost Mode — `LostModeStore` + `LostModeActivity` (showWhenLocked +
    turnScreenOn, back disabled, exit only via device-credential confirm
    + `lost_mode_off` command). Turns the lock on immediately when admin
    is active; full-screen-intent notification to surface the screen.
    Honest limit: Home cannot be blocked without a launcher — Lost Mode
    is a deterrent + contact channel, not a kiosk lock.
  - `SimMonitor` — compares subscription ids each worker run; records
    `sim_changed` (confirmed=false, always an indication) + local
    notification; trusted SIMs managed from Settings; needs
    READ_PHONE_STATE (consent step 4).
  - Security events — Room outbox (`security_events`, DB v2 with real
    Migration(1,2)); recorded on state change only (permission revoked,
    location disabled), low battery ≤15% max once/24h, sync gap >6h;
    uploaded by `UploadWorker` under the same data-loss rule as locations.
  - `UploadWorker` — now also polls pending commands + acks, uploads
    security events, and sends the extended heartbeat.
  - Command Center screen (`commands/{deviceId}/{deviceName}`) — Ring /
    Lock / Lost Mode ON-OFF + message field + command history; shield
    entry on each device row; Lock disabled with a hint when this phone
    lacks device admin.

### Test the new endpoints (BASE = deployed worker URL)

```sh
# create a command (ACCESS = user access token, DEVICE_ID = target device)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/commands \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"action":"ring"}'
# lost mode on with a message
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/commands \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"action":"lost_mode_on","payload":{"message":"This phone is lost. Please call 01800-000000."}}'
# command history + pending (DEVICE = device token) + ack
curl -s $BASE/api/v1/devices/$DEVICE_ID/commands -H "Authorization: Bearer $ACCESS"
curl -s $BASE/api/v1/devices/$DEVICE_ID/commands/pending -H "Authorization: Bearer $DEVICE"
curl -s -X POST $BASE/api/v1/commands/$COMMAND_ID/ack \
  -H "Authorization: Bearer $DEVICE" -H 'Content-Type: application/json' \
  -d "{\"device_id\":\"$DEVICE_ID\",\"status\":\"acked\",\"result\":\"Ringing\"}"
# security events (upload + list)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/security-events \
  -H "Authorization: Bearer $DEVICE" -H 'Content-Type: application/json' \
  -d '{"events":[{"event_type":"sim_changed","details":{"trusted":false},"confirmed":false}]}'
curl -s $BASE/api/v1/devices/$DEVICE_ID/security-events -H "Authorization: Bearer $ACCESS"
# extended heartbeat (new fields optional; old clients still work)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/heartbeat \
  -H "Authorization: Bearer $DEVICE" -H 'Content-Type: application/json' \
  -d '{"battery_level":72,"battery_charging":false,"network_type":"wifi","location_permission":"all","location_enabled":true}'
```

### Phase 2 stubbed / gaps

- **No push channel** — polling only (see latency note above). FCM /
  UnifiedPush is a future phase.
- **Ring needs tracking on or the app open** (Android 12+ background FGS
  restriction) — the command acks `failed` with an explanation otherwise.
- **No Android UI lists security events yet** — the repository method +
  endpoint exist; only the dashboard Alerts card shows them.
- **Idempotency race:** two concurrent creates with the same
  idempotency_key can both pass the existence check and hit the UNIQUE
  constraint (500). Rare; a retry returns the existing command.
- **Lost Mode is not a kiosk lock** (Home button still works); the phone
  is locked and location keeps uploading.
- Android still never compiled on this VM — CI is the first real build.

## What is done

- **Backend** (`backend/`, TypeScript + Hono + `@neondatabase/serverless`):
  all 17 MVP endpoints (auth, sessions, devices, locations, heartbeat,
  export, audit, privacy, health). WebCrypto only; no Node APIs.
  `DATABASE_URL` read from `env` only — no secret anywhere in the repo.
- **Dashboard** (`backend/public/index.html`, single file, served by the
  Worker at `GET /`): login/register, device list + add/unlink, MapLibre map
  with OSM raster tiles + attribution, history with pagination + path line,
  CSV/JSON export, sessions, audit, privacy, account deletion.
- **Android** (`android/`, Kotlin + Compose, MVVM, 28 files, 2,573 lines):
  login/register, device list, explicit consent screen (3 permission steps),
  foreground location service with persistent visible notification,
  Room outbox + WorkManager upload (rows deleted only after server confirms),
  MapLibre map + history, settings (privacy text, battery-optimization link,
  logout, account delete). CI workflow builds a debug APK on push.
- **Database** (`db/schema.sql`): users, sessions, devices, locations,
  audit_events + indexes. `gen_random_uuid()`, no extensions needed on Neon.

## Auth: client-side KDF (resolved 16 Nov 2026)

Server-side PBKDF2 is impossible on Workers Free: 600k iterations cost
≈ 150 ms CPU, but the free plan allows only 10 ms per request (Error 1102
above it), and Workers Paid ($5/mo) breaks the $0 rule. So the KDF moved
client-side. Contract:

- Client derives a 32-byte key with PBKDF2-SHA256, 600,000 iterations
  (OWASP minimum), from password + salt. Salt is 16 random bytes,
  hex-encoded (32 chars); derived key is hex-encoded (64 chars).
- The server NEVER sees the password. It stores `hex(SHA-256(derived_key))`
  in `users.password_hash` and the hex salt in `users.salt`. Verification
  is SHA-256 of the submitted derived key, compared in constant time —
  microseconds, far inside the 10 ms budget. A DB leak yields neither the
  password nor a login-capable value.
- `POST /auth/register` takes `{email, salt, derived_key}` (409 on
  duplicate email, as before).
- `GET /auth/salt?email=...` returns `{salt}`. For an UNKNOWN email it
  returns a deterministic fake salt `hex(SHA-256("openguard-salt:" +
  email))[0:32]`, so the response never reveals whether the email is
  registered (no user enumeration).
- `POST /auth/login` takes `{email, derived_key}`; `DELETE /auth/account`
  takes `{derived_key}` (client fetches the salt first). Same error shapes
  as before (401 invalid email or password, 403 incorrect password).
- Dashboard derives with WebCrypto (`crypto.subtle`, see
  `backend/public/index.html`); Android derives with
  `javax.crypto` PBKDF2WithHmacSHA256 in `core/security/PasswordKdf.kt`
  (built in, no new dependency, on `Dispatchers.Default`).
- Password length (8+) is enforced client-side only now (dashboard submit
  handler + `AuthRepository.register`); the server cannot see the password
  to check it.

## Deploy steps (parent)

1. **Apply the schema to Neon.** Project `quiet-unit-92878969` (Singapore).
   Paste `db/schema.sql` into the Neon SQL Editor and run it.
2. **Set the secret.** From `backend/`:
   `npx wrangler secret put DATABASE_URL` — paste the pooled connection
   string from the Neon console. Never put it in a file.
3. **Deploy.** From `backend/`: `npx wrangler deploy`
   (worker name `openguard-api`). Note the
   `https://openguard-api.<account>.workers.dev` URL.
4. **Smoke-test the live worker** (see "Test each endpoint" below),
   especially register/login/salt.
5. **Android:** set the real worker URL in
   `android/app/src/main/java/com/openguard/core/common/Constants.kt`
   (`API_BASE_URL`), then push to GitHub — `.github/workflows/android.yml`
   builds `app-debug.apk` on push to `main` touching `android/**`.

## Test each endpoint (BASE = deployed worker URL)

Auth now needs a client-derived key. Derive one with this node one-liner
(WebCrypto, node 16+; optional 2nd arg reuses a salt — same password + salt
always gives the same key):

```sh
eval $(node --input-type=module -e "import {webcrypto} from 'node:crypto'; const h=b=>[...b].map(x=>x.toString(16).padStart(2,'0')).join(''); const u=s=>new Uint8Array(s.match(/../g).map(x=>parseInt(x,16))); const salt=process.argv[2]?u(process.argv[2]):webcrypto.getRandomValues(new Uint8Array(16)); const k=await webcrypto.subtle.importKey('raw',new TextEncoder().encode(process.argv[1]),'PBKDF2',false,['deriveBits']); const d=new Uint8Array(await webcrypto.subtle.deriveBits({name:'PBKDF2',salt,iterations:600000,hash:'SHA-256'},k,256)); console.log('SALT='+h(salt)); console.log('KEY='+h(d));" 'Test1234!long')
# → sets $SALT (32 hex) and $KEY (64 hex)
```

```sh
# health
curl $BASE/api/v1/health
# register (save tokens)
curl -s -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"test@example.com\",\"salt\":\"$SALT\",\"derived_key\":\"$KEY\"}"
# salt lookup (login step 1) — also try an unknown email: it returns a fake
# salt instead of a 404, so it never reveals whether the email is registered
curl -s "$BASE/api/v1/auth/salt?email=test@example.com"
# login (derive with the salt returned above, then save tokens)
eval $(node --input-type=module -e "import {webcrypto} from 'node:crypto'; const h=b=>[...b].map(x=>x.toString(16).padStart(2,'0')).join(''); const u=s=>new Uint8Array(s.match(/../g).map(x=>parseInt(x,16))); const salt=process.argv[2]?u(process.argv[2]):webcrypto.getRandomValues(new Uint8Array(16)); const k=await webcrypto.subtle.importKey('raw',new TextEncoder().encode(process.argv[1]),'PBKDF2',false,['deriveBits']); const d=new Uint8Array(await webcrypto.subtle.deriveBits({name:'PBKDF2',salt,iterations:600000,hash:'SHA-256'},k,256)); console.log('SALT='+h(salt)); console.log('KEY='+h(d));" 'Test1234!long' "$SALT")
curl -s -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"test@example.com\",\"derived_key\":\"$KEY\"}"
# list devices (ACCESS = access_token from login)
curl -s $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS"
# register a device (save device_token — shown once)
curl -s -X POST $BASE/api/v1/devices -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Pixel test","model":"Pixel 8","os_version":"Android 14"}'
# upload locations (DEVICE = device_token)
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/locations \
  -H "Authorization: Bearer $DEVICE" -H 'Content-Type: application/json' \
  -d '{"locations":[{"latitude":22.3569,"longitude":91.7832,"accuracy":12,"recorded_at":"2026-11-16T15:00:00Z","provider":"gps"}]}'
# heartbeat
curl -s -X POST $BASE/api/v1/devices/$DEVICE_ID/heartbeat \
  -H "Authorization: Bearer $DEVICE" -H 'Content-Type: application/json' \
  -d '{"battery_level":72,"battery_charging":false,"network_type":"wifi"}'
# latest + history + export (user token)
curl -s $BASE/api/v1/devices/$DEVICE_ID/latest -H "Authorization: Bearer $ACCESS"
curl -s "$BASE/api/v1/devices/$DEVICE_ID/locations?limit=10" -H "Authorization: Bearer $ACCESS"
curl -s "$BASE/api/v1/devices/$DEVICE_ID/export?format=csv" -H "Authorization: Bearer $ACCESS"
# sessions, audit, privacy
curl -s $BASE/api/v1/auth/sessions -H "Authorization: Bearer $ACCESS"
curl -s $BASE/api/v1/audit -H "Authorization: Bearer $ACCESS"
curl -s $BASE/api/v1/privacy -H "Authorization: Bearer $ACCESS"
# logout, then delete the test account (derived-key confirmation — re-derive
# with the salt from /auth/salt first, same one-liner as login above)
curl -s -X POST $BASE/api/v1/auth/logout -H "Authorization: Bearer $ACCESS"
curl -s -X DELETE $BASE/api/v1/auth/account -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -d "{\"derived_key\":\"$KEY\"}"
```

Also open `$BASE/` in a browser and walk the dashboard: register → add
device → check the map, history, sessions, audit, privacy panels.

## What is stubbed / gaps (MVP-accepted)

- **No retention purge:** `retention_days: 30` is declared policy only;
  nothing deletes old locations yet. Needs a scheduled Worker or Neon cron.
- **No refresh-token reuse detection** (stolen rotated token not flagged).
- **History pagination** keys on `recorded_at` only; rows sharing an
  identical timestamp could skip/duplicate across pages. Keyset on
  `(recorded_at, id)` would fix it.
- **Register + session creation isn't atomic** (no transaction); a failed
  session insert leaves a user who must log in. Recoverable.
- **No `account_delete` audit row survives** — audit rows cascade with the
  user. All other audit events are recorded.
- **Android:** `DeviceKeys.kt` is a stub (Phase 2); no boot receiver
  (tracking doesn't auto-resume after reboot); no launcher icon; Android
  does not call `/audit` or `/privacy` (Settings shows static privacy text);
  OSM tiles direct from tile.openstreetmap.org (fine for personal MVP).
- **Android toolchain pinned low on purpose:** compileSdk/target 34,
  AGP 8.5.2, Gradle 8.7, Kotlin 2.0.21, Compose BOM 2024.09.00. The latest
  2026 libraries need compileSdk 35–37 + AGP 9.x. Raising the toolchain is
  a separate future step (documented in `android/README.md`).
- **MapLibre advisories:** dashboard uses maplibre-gl 4.7.1 (UMD). A
  CVSS 10.0 XSS (CVE-2026-85061) affects ≤ 6.4.0, but the exploit path
  (untrusted attribution strings) is absent here — attribution is a
  hardcoded literal, no popups. Upgrading to ≥ 6.4.1 needs the v6 ESM-only
  migration; schedule it, don't block MVP. Android uses MapLibre 11.11.0
  (canonical `android-sdk` artifact).
- **Wrangler 3.114.17 installed;** wrangler 4.x exists. Upgrading is a
  separate decision.
- **No LICENSE file yet.** Blueprint says Apache-2.0 for code, CC BY 4.0
  for docs — add before any public release.

## NOT built in this pass (per MVP scope)

Vault, SMS commands, FMD/Traccar coupling, geofencing,
advanced analytics, passkeys/TOTP. (Ring, lock and Lost Mode arrived in
Phase 2, above; remote erase, recovery codes and remote sign-out arrived
in Phase 3.) No secret camera, no stealth, no hidden surveillance
anywhere in the code.

## Rate limits (in-memory, per-isolate)

Auth endpoints 10/min/IP (includes step-up and recover); location upload
120/min/device; command creation 20/min/user; erase 1/hour/user.
Documented in `src/lib/ratelimit.ts`.
