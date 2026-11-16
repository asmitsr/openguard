# OpenGuard — Build Notes (MVP, Phase 0 + Phase 1)

Built 16 Nov 2026. Backend + dashboard verified (typecheck green, local
`wrangler dev` smoke test passed, docs audit passed). Android written but
never compiled (no SDK/Java on this VM) — first real build is GitHub Actions.

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

Vault, ring/lock, remote erase, Lost Mode, SMS commands, FMD/Traccar
coupling, geofencing, advanced analytics, passkeys/TOTP. No secret camera,
no stealth, no hidden surveillance anywhere in the code.

## Rate limits (in-memory, per-isolate)

Auth endpoints 10/min/IP; location upload 120/min/device. Documented in
`src/lib/ratelimit.ts`.
