import { Hono, type Context } from 'hono'
import { NeonDbError } from '@neondatabase/serverless'
import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type RegistrationResponseJSON,
} from '@simplewebauthn/server'
import { isoBase64URL } from '@simplewebauthn/server/helpers'
import { db, type Env } from '../lib/db'
import { authenticateUser, b64url, clientIp, createSession } from '../lib/auth'
import { audit } from '../lib/audit'
import { err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

// ---------------------------------------------------------------------------
// Relying Party config. Workers reads these from wrangler.toml [vars];
// the self-hosted Docker path sets them via env (see server.ts and
// .env.example). Unset anywhere, they default to the Workers deployment.
// ---------------------------------------------------------------------------

const DEFAULT_RP_ID = 'openguard-api.asmitsr.workers.dev'
const DEFAULT_RP_NAME = 'OpenGuard'
const DEFAULT_RP_ORIGIN = 'https://openguard-api.asmitsr.workers.dev'

function rpConfig(env: Env): { id: string; name: string; origin: string } {
  return {
    id: env.RP_ID ?? DEFAULT_RP_ID,
    name: env.RP_NAME ?? DEFAULT_RP_NAME,
    origin: env.RP_ORIGIN ?? DEFAULT_RP_ORIGIN,
  }
}

// ---------------------------------------------------------------------------
// Challenge store: in-memory, single-use, 5-minute TTL, same style as
// ratelimit.ts (occasional sweep so the map can't grow without bound).
// Keys: `reg:<userId>` for registration, `login:<lowercased email>` for
// login. A challenge is deleted on EVERY verify attempt, success or
// failure, so it can never be replayed.
//
// MULTI-ISOLATE CAVEAT: this map lives in one isolate's memory, so a
// challenge issued by one isolate may not verify on another (Workers may
// route the verify request elsewhere). The clients retry the whole
// challenge+verify flow once on failure. Use Workers KV or Durable
// Objects for exact global challenges later.
// ---------------------------------------------------------------------------

const CHALLENGE_TTL_MS = 5 * 60 * 1000

const challenges = new Map<string, { challenge: string; expiresAt: number }>()

function storeChallenge(key: string, challenge: string): void {
  challenges.set(key, { challenge, expiresAt: Date.now() + CHALLENGE_TTL_MS })
  if (challenges.size > 1000 && Math.random() < 0.05) {
    const now = Date.now()
    for (const [k, v] of challenges) if (v.expiresAt <= now) challenges.delete(k)
  }
}

function takeChallenge(key: string): string | null {
  const entry = challenges.get(key)
  challenges.delete(key)
  if (!entry || entry.expiresAt <= Date.now()) return null
  return entry.challenge
}

// Passkey login attempts per IP: 10/min, same budget as /auth/*.
function passkeyLimited(c: Context<any>): boolean {
  return rateLimited(`passkey:${clientIp(c) ?? 'unknown'}`, 10, 60_000)
}

function userAgent(c: Context<any>): string | null {
  return c.req.header('User-Agent')?.slice(0, 500) ?? null
}

// transports is stored as a JSON string ('["usb","nfc"]'); parse it back,
// tolerating NULL and anything malformed.
function parseTransports(raw: string | null): string[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []
  } catch {
    return []
  }
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

type PasskeyRow = {
  id: string
  user_id: string
  credential_id: string
  public_key: string
  counter: number | string
  transports: string | null
}

// Registration challenge. Authed: a passkey is always added to the signed-in
// account. The generateRegistrationOptions output is returned verbatim.
app.post('/register/challenge', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rp = rpConfig(c.env)
  const rows = await db(c.env)`
    SELECT credential_id, transports FROM passkeys WHERE user_id = ${auth.user.id}`
  const options = await generateRegistrationOptions({
    rpName: rp.name,
    rpID: rp.id,
    userName: auth.user.email,
    // @simplewebauthn/server v14 rejects string userIDs (it throws), so the
    // user UUID is passed as UTF-8 bytes; the emitted user.id is the
    // base64url of the UUID string. .slice() narrows TextEncoder's
    // Uint8Array<ArrayBufferLike> to the Uint8Array<ArrayBuffer> v14 wants.
    userID: new TextEncoder().encode(auth.user.id).slice(),
    userDisplayName: auth.user.email,
    attestationType: 'none',
    excludeCredentials: rows.map((r) => {
      const transports = parseTransports(r.transports as string | null)
      return transports.length
        ? { id: r.credential_id as string, transports }
        : { id: r.credential_id as string }
    }),
    authenticatorSelection: { residentKey: 'preferred', userVerification: 'preferred' },
  })
  storeChallenge(`reg:${auth.user.id}`, options.challenge)
  return c.json(options)
})

// Registration verify. Body: { name?: string, credential:
// RegistrationResponseJSON } — the @simplewebauthn/browser output verbatim.
// verifyRegistrationResponse throws on any mismatch, so it is wrapped;
// a 409 means this authenticator is already registered to this account.
app.post('/register/verify', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ name?: unknown; credential?: unknown }>(c)
  const name = body?.name
  if (name !== undefined && (typeof name !== 'string' || name.length < 1 || name.length > 100)) {
    return err(c, 400, 'name must be 1-100 characters')
  }
  const credential = body?.credential as RegistrationResponseJSON | undefined
  if (!credential || typeof credential !== 'object') {
    return err(c, 400, 'credential required')
  }
  const expectedChallenge = takeChallenge(`reg:${auth.user.id}`)
  if (!expectedChallenge) {
    return err(c, 400, 'challenge expired or not found; request a new challenge')
  }
  const rp = rpConfig(c.env)
  let verification
  try {
    verification = await verifyRegistrationResponse({
      response: credential,
      expectedChallenge,
      expectedOrigin: rp.origin,
      expectedRPID: rp.id,
      requireUserVerification: false,
    })
  } catch {
    return err(c, 400, 'invalid registration response')
  }
  if (!verification.verified || !verification.registrationInfo) {
    return err(c, 400, 'invalid registration response')
  }
  const cred = verification.registrationInfo.credential
  try {
    const rows = await db(c.env)`
      INSERT INTO passkeys (user_id, credential_id, public_key, counter, transports, name)
      VALUES (${auth.user.id}, ${cred.id}, ${b64url(cred.publicKey)}, ${cred.counter},
              ${JSON.stringify(cred.transports ?? [])}, ${name ?? 'Passkey'})
      RETURNING id, name, created_at`
    const row = rows[0] as { id: string; name: string; created_at: string }
    await audit(c.env, auth.user.id, 'passkey_register')
    return c.json({ id: row.id, name: row.name, created_at: row.created_at }, 201)
  } catch (e) {
    // The same authenticator registered twice (or raced): credential_id is
    // UNIQUE, so the second insert fails with 23505 — still a 409.
    if (e instanceof NeonDbError && e.code === '23505') {
      return err(c, 409, 'this passkey is already registered')
    }
    throw e
  }
})

// Login challenge. Public. ANTI-ENUMERATION: for an unknown email, or an
// email with no passkeys, the response has the SAME shape — a fresh random
// challenge and allowCredentials: [] — and a challenge is still stored, so
// the verify step fails at credential lookup, never at challenge lookup.
// The response never reveals whether the email exists or has passkeys
// (same pattern as GET /auth/salt's fake salt).
app.post('/login/challenge', async (c) => {
  if (passkeyLimited(c)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ email?: unknown }>(c)
  const email = body?.email
  if (typeof email !== 'string' || !email || email.length > 254) {
    return err(c, 400, 'email required')
  }
  const rp = rpConfig(c.env)
  const sql = db(c.env)
  // Exact-match lookup, same as /auth/login and /auth/salt: registration
  // never lowercases emails, so neither does lookup. Only the challenge
  // key is lowercased (so challenge and verify always agree on the key).
  const users = await sql`SELECT id FROM users WHERE email = ${email}`
  const user = users[0] as { id: string } | undefined
  const rows = user
    ? await sql`SELECT credential_id, transports FROM passkeys WHERE user_id = ${user.id}`
    : []
  const options = await generateAuthenticationOptions({
    rpID: rp.id,
    allowCredentials: rows.map((r) => {
      const transports = parseTransports(r.transports as string | null)
      return transports.length
        ? { id: r.credential_id as string, transports }
        : { id: r.credential_id as string }
    }),
    userVerification: 'preferred',
  })
  storeChallenge(`login:${email.toLowerCase()}`, options.challenge)
  return c.json(options)
})

// Login verify. Public. Body: { email, credential:
// AuthenticationResponseJSON }. Every failure — unknown email, no passkey,
// credential owned by another user, bad assertion, bad/expired challenge —
// returns the same 401 'invalid email or passkey'. On success the response
// is IDENTICAL in shape to POST /auth/login. Note: requireUserVerification
// is left at its default (true) here, unlike registration — a login must
// prove the user, not just possession of the authenticator.
app.post('/login/verify', async (c) => {
  const body = await jsonBody<{ email?: unknown; credential?: unknown }>(c)
  const email = body?.email
  const credential = body?.credential as AuthenticationResponseJSON | undefined
  if (
    typeof email !== 'string' ||
    !email ||
    !credential ||
    typeof credential !== 'object'
  ) {
    return err(c, 400, 'email and credential required')
  }
  const expectedChallenge = takeChallenge(`login:${email.toLowerCase()}`)
  const sql = db(c.env)
  const credId = typeof credential.id === 'string' ? credential.id : ''
  const users = await sql`SELECT id, email FROM users WHERE email = ${email}`
  const user = users[0] as { id: string; email: string } | undefined
  const rows = credId
    ? await sql`
        SELECT id, user_id, credential_id, public_key, counter, transports
        FROM passkeys WHERE credential_id = ${credId}`
    : []
  const passkey = rows[0] as unknown as PasskeyRow | undefined
  if (!expectedChallenge || !user || !passkey || passkey.user_id !== user.id) {
    return err(c, 401, 'invalid email or passkey')
  }
  const rp = rpConfig(c.env)
  let verification
  try {
    verification = await verifyAuthenticationResponse({
      response: credential,
      expectedChallenge,
      expectedOrigin: rp.origin,
      expectedRPID: rp.id,
      credential: {
        id: passkey.credential_id,
        publicKey: isoBase64URL.toBuffer(passkey.public_key),
        counter: Number(passkey.counter),
        transports: parseTransports(passkey.transports),
      },
    })
  } catch {
    return err(c, 401, 'invalid email or passkey')
  }
  if (!verification.verified) return err(c, 401, 'invalid email or passkey')
  await sql`
    UPDATE passkeys SET counter = ${verification.authenticationInfo.newCounter},
                        last_used_at = now()
    WHERE id = ${passkey.id}`
  const { accessToken, refreshToken } = await createSession(
    c.env,
    user.id,
    userAgent(c),
    clientIp(c),
  )
  await audit(c.env, user.id, 'passkey_login', null, { ip: clientIp(c) })
  return c.json({
    access_token: accessToken,
    refresh_token: refreshToken,
    user: { id: user.id, email: user.email },
  })
})

// List the signed-in user's passkeys, newest first. NEVER exposes
// public_key, credential_id, counter, or transports.
app.get('/', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    SELECT id, name, created_at, last_used_at FROM passkeys
    WHERE user_id = ${auth.user.id} ORDER BY created_at DESC`
  return c.json(
    rows.map((r) => ({
      id: r.id,
      name: r.name,
      created_at: r.created_at,
      last_used_at: r.last_used_at,
    })),
  )
})

// Revoke one passkey. Deletes only where id + user_id match; anything else
// is a 404. Revoking does NOT touch sessions — other signed-in sessions
// stay valid until they expire or are revoked via /auth/sessions.
app.delete('/:id', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const id = c.req.param('id')
  // A non-UUID id can never match; answer 404 instead of letting Postgres
  // raise on the uuid comparison.
  if (!UUID_RE.test(id)) return err(c, 404, 'passkey not found')
  const rows = await db(c.env)`
    DELETE FROM passkeys WHERE id = ${id} AND user_id = ${auth.user.id} RETURNING id`
  if (!rows.length) return err(c, 404, 'passkey not found')
  await audit(c.env, auth.user.id, 'passkey_revoke', null, { passkey_id: id })
  return c.body(null, 204)
})

export default app
