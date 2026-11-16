# OpenGuard — Build Notes (MVP, Phase 0 + Phase 1)

Built 16 Nov 2026. Backend + dashboard verified (typecheck green, local
`wrangler dev` smoke test passed, docs audit passed). Android written but
never compiled (no SDK/Java on this VM) — first real build is GitHub Actions.

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
worker). Commands expire after 5 minutes, so a phone that is offline for
more than 5 minutes will find the command expired on its next poll and the
owner must re-send. Push is never command authentication: the phone
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

Vault, remote erase, SMS commands, FMD/Traccar coupling, geofencing,
advanced analytics, passkeys/TOTP. (Ring, lock and Lost Mode arrived in
Phase 2, above.) No secret camera, no stealth, no hidden surveillance
anywhere in the code.

## Rate limits (in-memory, per-isolate)

Auth endpoints 10/min/IP; location upload 120/min/device. Documented in
`src/lib/ratelimit.ts`.
