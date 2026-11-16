import type { Context } from 'hono'
import { db, type Env } from './db'

// Helpers take Context<any>: Hono's Context varies by route path, and `any`
// keeps one helper callable from every route.

// ---------------------------------------------------------------------------
// Passwords: CLIENT-SIDE KDF. Workers Free allows only 10 ms CPU per request,
// but PBKDF2-SHA256 at 600k iterations costs ~150 ms — the server cannot run
// it (Error 1102 above 10 ms). So the client derives a 32-byte key with
// PBKDF2-SHA256 (600k iterations, OWASP 2023 guidance) from the password +
// a 16-byte random salt, and sends only the hex-encoded derived key. The
// server NEVER sees the password: it stores hex(SHA-256(derived_key)) in
// users.password_hash and the hex salt in users.salt. A DB leak therefore
// yields neither the password nor a login-capable value, and SHA-256 of 32
// bytes costs microseconds — far inside the 10 ms budget.
// ---------------------------------------------------------------------------

export function b64url(bytes: Uint8Array): string {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
}

function hexToBytes(hex: string): Uint8Array | null {
  if (hex.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(hex)) return null
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

export async function sha256HexBytes(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

// Hashes a client-derived key (64 hex chars = 32 bytes) for storage.
// Returns null when the input is not exactly 32 bytes of hex.
export async function hashDerivedKey(derivedKeyHex: string): Promise<string | null> {
  const bytes = hexToBytes(derivedKeyHex)
  if (!bytes || bytes.length !== 32) return null
  return sha256HexBytes(bytes)
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i]
  return diff === 0
}

// Compares SHA-256(submitted derived key) against the stored hash in
// constant time. Returns false for malformed input or a length mismatch.
export async function verifyDerivedKey(derivedKeyHex: string, stored: string): Promise<boolean> {
  const hash = await hashDerivedKey(derivedKeyHex)
  if (!hash || hash.length !== stored.length) return false
  return timingSafeEqual(new TextEncoder().encode(hash), new TextEncoder().encode(stored))
}

// ---------------------------------------------------------------------------
// Tokens: opaque, random, stored only as SHA-256 hashes.
// Access token: 15 min (sessions.expires_at). Refresh token: 30 days from
// sessions.created_at, renewed on each rotation (sliding window).
// ---------------------------------------------------------------------------

export const REFRESH_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000

export function newToken(): string {
  return b64url(crypto.getRandomValues(new Uint8Array(32)))
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export type UserRow = { id: string; email: string }
export type DeviceRow = {
  id: string
  user_id: string
  name: string
  model: string | null
  os_version: string | null
  last_seen_at: string | null
  battery_level: number | null
  battery_charging: boolean | null
  network_type: string | null
  created_at: string
}

export type AuthedUser = { user: UserRow; sessionId: string }

function bearerToken(c: Context<any>): string | null {
  const h = c.req.header('Authorization')
  if (!h || !h.startsWith('Bearer ')) return null
  const token = h.slice(7).trim()
  return token ? token : null
}

// Validates a user access token. Returns null when missing, unknown or expired.
export async function authenticateUser(c: Context<any>): Promise<AuthedUser | null> {
  const token = bearerToken(c)
  if (!token) return null
  const sql = db(c.env)
  const rows = await sql`
    SELECT s.id AS session_id, s.expires_at, u.id, u.email
    FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.token_hash = ${await sha256Hex(token)}`
  const row = rows[0]
  if (!row) return null
  if (new Date(row.expires_at as string).getTime() <= Date.now()) {
    // Opportunistic cleanup of the expired session.
    await sql`DELETE FROM sessions WHERE id = ${row.session_id as string}`
    return null
  }
  await sql`UPDATE sessions SET last_used_at = now() WHERE id = ${row.session_id as string}`
  return {
    user: { id: row.id as string, email: row.email as string },
    sessionId: row.session_id as string,
  }
}

// Validates a per-device token for one specific device. A device token for
// device A can never act on device B.
export async function authenticateDevice(
  c: Context<any>,
  deviceId: string,
): Promise<DeviceRow | null> {
  const token = bearerToken(c)
  if (!token) return null
  const sql = db(c.env)
  const rows = await sql`
    SELECT id, user_id, name, model, os_version, last_seen_at, battery_level,
           battery_charging, network_type, created_at
    FROM devices WHERE id = ${deviceId} AND token_hash = ${await sha256Hex(token)}`
  return (rows[0] as unknown as DeviceRow) ?? null
}

// Ownership check for user-authenticated device endpoints.
export async function ownsDevice(env: Env, userId: string, deviceId: string): Promise<boolean> {
  const rows = await db(env)`SELECT id FROM devices WHERE id = ${deviceId} AND user_id = ${userId}`
  return rows.length > 0
}

// Creates a session and returns the raw tokens (shown once, never stored raw).
export async function createSession(
  env: Env,
  userId: string,
  userAgent: string | null,
  ip: string | null,
): Promise<{ accessToken: string; refreshToken: string }> {
  const sql = db(env)
  const accessToken = newToken()
  const refreshToken = newToken()
  await sql`
    INSERT INTO sessions (user_id, token_hash, refresh_token_hash, user_agent, ip, expires_at)
    VALUES (${userId}, ${await sha256Hex(accessToken)}, ${await sha256Hex(refreshToken)},
            ${userAgent}, ${ip}, now() + interval '15 minutes')`
  return { accessToken, refreshToken }
}

export function clientIp(c: Context<any>): string | null {
  return (
    c.req.header('CF-Connecting-IP') ?? c.req.header('X-Forwarded-For')?.split(',')[0]?.trim() ?? null
  )
}
