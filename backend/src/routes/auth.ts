import { Hono, type Context } from 'hono'
import { NeonDbError } from '@neondatabase/serverless'
import { db, type Env } from '../lib/db'
import {
  REFRESH_TOKEN_TTL_MS,
  authenticateUser,
  clientIp,
  createSession,
  hashDerivedKey,
  newToken,
  sha256Hex,
  verifyDerivedKey,
  verifyStepUpToken,
} from '../lib/auth'
import { audit } from '../lib/audit'
import { err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// Dummy hash (64 zero hex chars — not a SHA-256 output of anything) used
// only so a login for a non-existent email takes the same time as a real
// derived-key check. Not a credential: no user row ever carries this hash.
const DUMMY_HASH = '0'.repeat(64)

function validEmail(v: unknown): v is string {
  return typeof v === 'string' && v.length <= 254 && EMAIL_RE.test(v)
}

// Salt: 16 bytes hex-encoded. Derived key: 32 bytes hex-encoded.
const HEX32_RE = /^[0-9a-f]{32}$/
const HEX64_RE = /^[0-9a-f]{64}$/

function validSalt(v: unknown): v is string {
  return typeof v === 'string' && HEX32_RE.test(v)
}

function validDerivedKey(v: unknown): v is string {
  return typeof v === 'string' && HEX64_RE.test(v)
}

// Auth attempts per IP: 10/min, shared by register/login/refresh/salt.
function authLimited(c: Context<any>): boolean {
  return rateLimited(`auth:${clientIp(c) ?? 'unknown'}`, 10, 60_000)
}

function userAgent(c: Context<any>): string | null {
  return c.req.header('User-Agent')?.slice(0, 500) ?? null
}

app.post('/register', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ email?: unknown; salt?: unknown; derived_key?: unknown }>(c)
  const { email, salt, derived_key: derivedKey } = body ?? {}
  if (!validEmail(email) || !validSalt(salt) || !validDerivedKey(derivedKey)) {
    return err(c, 400, 'valid email, salt (32 hex chars) and derived_key (64 hex chars) required')
  }
  const passwordHash = await hashDerivedKey(derivedKey)
  if (!passwordHash) return err(c, 400, 'invalid derived_key')
  const sql = db(c.env)
  const existing = await sql`SELECT id FROM users WHERE email = ${email}`
  if (existing.length) return err(c, 409, 'email already registered')
  let rows
  try {
    rows = await sql`
      INSERT INTO users (email, password_hash, salt)
      VALUES (${email}, ${passwordHash}, ${salt})
      RETURNING id, email`
  } catch (e) {
    // Two concurrent registers for the same email both pass the pre-check;
    // the UNIQUE constraint rejects the second (23505) — still a 409.
    if (e instanceof NeonDbError && e.code === '23505') {
      return err(c, 409, 'email already registered')
    }
    throw e
  }
  const user = rows[0] as { id: string; email: string }
  const { accessToken, refreshToken } = await createSession(
    c.env,
    user.id,
    userAgent(c),
    clientIp(c),
  )
  await audit(c.env, user.id, 'register')
  return c.json(
    { user: { id: user.id, email: user.email }, access_token: accessToken, refresh_token: refreshToken },
    201,
  )
})

// Returns the user's salt so the client can derive its key before login.
// For an unknown email returns a deterministic fake salt, so the response
// never reveals whether the email is registered (no user enumeration).
app.get('/salt', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const email = c.req.query('email')
  if (!validEmail(email)) return err(c, 400, 'valid email required')
  const rows = await db(c.env)`SELECT salt FROM users WHERE email = ${email}`
  const row = rows[0] as { salt: string } | undefined
  if (row) return c.json({ salt: row.salt })
  const fake = (await sha256Hex(`openguard-salt:${email}`)).slice(0, 32)
  return c.json({ salt: fake })
})

app.post('/login', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ email?: unknown; derived_key?: unknown }>(c)
  const { email, derived_key: derivedKey } = body ?? {}
  if (!validEmail(email) || typeof derivedKey !== 'string') {
    return err(c, 400, 'email and derived_key required')
  }
  const sql = db(c.env)
  const rows = await sql`SELECT id, email, password_hash FROM users WHERE email = ${email}`
  const user = rows[0] as { id: string; email: string; password_hash: string } | undefined
  const ok = await verifyDerivedKey(derivedKey, user?.password_hash ?? DUMMY_HASH)
  if (!user || !ok) return err(c, 401, 'invalid email or password')
  const { accessToken, refreshToken } = await createSession(
    c.env,
    user.id,
    userAgent(c),
    clientIp(c),
  )
  await audit(c.env, user.id, 'login', null, { ip: clientIp(c) })
  return c.json({
    access_token: accessToken,
    refresh_token: refreshToken,
    user: { id: user.id, email: user.email },
  })
})

app.post('/refresh', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ refresh_token?: unknown }>(c)
  const refreshTokenIn = body?.refresh_token
  if (typeof refreshTokenIn !== 'string' || !refreshTokenIn) {
    return err(c, 400, 'refresh_token required')
  }
  const sql = db(c.env)
  const rows = await sql`
    SELECT id, created_at FROM sessions WHERE refresh_token_hash = ${await sha256Hex(refreshTokenIn)}`
  const session = rows[0] as { id: string; created_at: string } | undefined
  if (!session) return err(c, 401, 'invalid refresh token')
  if (new Date(session.created_at).getTime() + REFRESH_TOKEN_TTL_MS <= Date.now()) {
    await sql`DELETE FROM sessions WHERE id = ${session.id}`
    return err(c, 401, 'refresh token expired')
  }
  // Rotate both tokens. created_at is renewed, so the 30-day refresh window
  // slides with use; expires_at is the new 15-min access-token expiry.
  const accessToken = newToken()
  const refreshToken = newToken()
  await sql`
    UPDATE sessions
    SET token_hash = ${await sha256Hex(accessToken)},
        refresh_token_hash = ${await sha256Hex(refreshToken)},
        created_at = now(), last_used_at = now(),
        expires_at = now() + interval '15 minutes'
    WHERE id = ${session.id}`
  return c.json({ access_token: accessToken, refresh_token: refreshToken })
})

app.post('/logout', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  await db(c.env)`DELETE FROM sessions WHERE id = ${auth.sessionId}`
  await audit(c.env, auth.user.id, 'logout')
  return c.body(null, 204)
})

app.get('/sessions', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    SELECT id, created_at, last_used_at, user_agent, ip
    FROM sessions WHERE user_id = ${auth.user.id} ORDER BY last_used_at DESC`
  return c.json(
    rows.map((r) => ({
      id: r.id,
      created_at: r.created_at,
      last_used_at: r.last_used_at,
      user_agent: r.user_agent,
      ip: r.ip,
      current: r.id === auth.sessionId,
    })),
  )
})

app.delete('/sessions/:id', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const sessionId = c.req.param('id')
  const rows = await db(c.env)`
    DELETE FROM sessions WHERE id = ${sessionId} AND user_id = ${auth.user.id} RETURNING id`
  if (!rows.length) return err(c, 404, 'session not found')
  await audit(c.env, auth.user.id, 'session_revoke', null, { session_id: sessionId })
  return c.body(null, 204)
})

app.delete('/account', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ derived_key?: unknown }>(c)
  const derivedKey = body?.derived_key
  if (typeof derivedKey !== 'string') return err(c, 400, 'derived_key required')
  const rows = await db(c.env)`SELECT password_hash FROM users WHERE id = ${auth.user.id}`
  const user = rows[0] as { password_hash: string } | undefined
  if (!user || !(await verifyDerivedKey(derivedKey, user.password_hash))) {
    return err(c, 403, 'incorrect password')
  }
  // Cascades delete sessions, devices, locations and audit rows.
  // (No 'account_delete' audit row can survive: it would cascade with the user.)
  await db(c.env)`DELETE FROM users WHERE id = ${auth.user.id}`
  return c.body(null, 204)
})

// Step-up: re-verifies the password (via the client-derived key, same as
// DELETE /account) and returns a single-use 10-minute token that unlocks
// recovery-code generation and remote erase.
app.post('/step-up', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ derived_key?: unknown }>(c)
  const derivedKey = body?.derived_key
  if (typeof derivedKey !== 'string') return err(c, 400, 'derived_key required')
  const rows = await db(c.env)`SELECT password_hash FROM users WHERE id = ${auth.user.id}`
  const user = rows[0] as { password_hash: string } | undefined
  if (!user || !(await verifyDerivedKey(derivedKey, user.password_hash))) {
    return err(c, 403, 'incorrect password')
  }
  const token = newToken()
  await db(c.env)`
    INSERT INTO step_up_tokens (user_id, token_hash, expires_at)
    VALUES (${auth.user.id}, ${await sha256Hex(token)}, now() + interval '10 minutes')`
  await audit(c.env, auth.user.id, 'step_up')
  return c.json({ step_up_token: token, expires_in: 600 }, 200, { 'Cache-Control': 'no-store' })
})

app.post('/sessions/revoke-others', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    DELETE FROM sessions WHERE user_id = ${auth.user.id} AND id != ${auth.sessionId} RETURNING id`
  await audit(c.env, auth.user.id, 'sessions_revoke_others', null, { revoked: rows.length })
  return c.json({ revoked: rows.length })
})

// Recovery codes: 12 chars from a 32-char alphabet with no confusables
// (no 0/O, 1/I/L), formatted XXXX-XXXX-XXXX. Only SHA-256 hashes are stored;
// the codes themselves are returned once, at generation.
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function newRecoveryCode(): string {
  // 256 % 32 == 0, so byte % 32 is unbiased.
  const bytes = crypto.getRandomValues(new Uint8Array(12))
  const raw = [...bytes].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('')
  return `${raw.slice(0, 4)}-${raw.slice(4, 8)}-${raw.slice(8)}`
}

function normalizeRecoveryCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, '')
}

app.post('/recovery-codes/generate', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ step_up_token?: unknown }>(c)
  const stepUpToken = body?.step_up_token
  if (typeof stepUpToken !== 'string' || !(await verifyStepUpToken(c.env, auth.user.id, stepUpToken))) {
    return err(c, 403, 'step-up authentication required')
  }
  const codes = Array.from({ length: 10 }, newRecoveryCode)
  const hashes = await Promise.all(codes.map((code) => sha256Hex(normalizeRecoveryCode(code))))
  const sql = db(c.env)
  // Regenerating invalidates the old codes.
  await sql`DELETE FROM recovery_codes WHERE user_id = ${auth.user.id}`
  const params: unknown[] = []
  const tuples = hashes.map((h, i) => {
    params.push(auth.user.id, h)
    return `($${i * 2 + 1}, $${i * 2 + 2})`
  })
  await sql(`INSERT INTO recovery_codes (user_id, code_hash) VALUES ${tuples.join(',')}`, params)
  await audit(c.env, auth.user.id, 'recovery_codes_generate')
  return c.json({ codes }, 200, { 'Cache-Control': 'no-store' })
})

app.get('/recovery-codes', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    SELECT COUNT(*)::int AS n FROM recovery_codes
    WHERE user_id = ${auth.user.id} AND used = false`
  return c.json({ remaining: (rows[0] as { n: number }).n })
})

// Password recovery with a recovery code. No auth: the user lost their
// password. The code is claimed atomically (used=false -> used=true), so a
// code works exactly once. Unknown email and bad code give the same 401.
app.post('/recover', async (c) => {
  if (authLimited(c)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{
    email?: unknown
    code?: unknown
    salt?: unknown
    derived_key?: unknown
  }>(c)
  const { email, code, salt, derived_key: derivedKey } = body ?? {}
  if (!validEmail(email) || typeof code !== 'string' || !validSalt(salt) || !validDerivedKey(derivedKey)) {
    return err(
      c,
      400,
      'valid email, recovery code, salt (32 hex chars) and derived_key (64 hex chars) required',
    )
  }
  const passwordHash = await hashDerivedKey(derivedKey)
  if (!passwordHash) return err(c, 400, 'invalid derived_key')
  const sql = db(c.env)
  const users = await sql`SELECT id FROM users WHERE email = ${email}`
  const user = users[0] as { id: string } | undefined
  const claimed = user
    ? await sql`
        UPDATE recovery_codes SET used = true, used_at = now()
        WHERE user_id = ${user.id}
          AND code_hash = ${await sha256Hex(normalizeRecoveryCode(code))}
          AND used = false
        RETURNING id`
    : []
  if (!user || !claimed.length) return err(c, 401, 'invalid email or recovery code')
  await sql`UPDATE users SET password_hash = ${passwordHash}, salt = ${salt} WHERE id = ${user.id}`
  await sql`DELETE FROM sessions WHERE user_id = ${user.id}`
  await audit(c.env, user.id, 'password_recover')
  return c.body(null, 204)
})

export default app
