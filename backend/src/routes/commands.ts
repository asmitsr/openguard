import { Hono } from 'hono'
import { db, type Env } from '../lib/db'
import { authenticateDevice, authenticateUser, newToken, ownsDevice } from '../lib/auth'
import { audit } from '../lib/audit'
import { clampLimit, err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

const ACTIONS = ['ring', 'lock', 'lost_mode_on', 'lost_mode_off'] as const
type Action = (typeof ACTIONS)[number]

const EVENT_TYPES = [
  'sim_changed',
  'admin_disabled',
  'location_permission_revoked',
  'location_disabled',
  'low_battery',
  'sync_gap',
] as const

function isAction(v: unknown): v is Action {
  return typeof v === 'string' && (ACTIONS as readonly string[]).includes(v)
}

type CommandRow = {
  id: string
  device_id: string
  action: string
  payload: unknown
  status: string
  nonce: string
  issued_at: string
  expires_at: string
}

function publicCommand(r: CommandRow) {
  return {
    id: r.id,
    device_id: r.device_id,
    action: r.action,
    payload: r.payload,
    status: r.status,
    nonce: r.nonce,
    issued_at: r.issued_at,
    expires_at: r.expires_at,
  }
}

// POST /devices/:id/commands — user issues a remote command for one device.
app.post('/devices/:id/commands', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  if (rateLimited(`cmd:${auth.user.id}`, 20, 60_000)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{
    action?: unknown
    payload?: unknown
    idempotency_key?: unknown
  }>(c)
  const action = body?.action
  const payload = body?.payload as Record<string, unknown> | null | undefined
  const idempotencyKey = body?.idempotency_key
  const message = payload?.message
  if (
    !isAction(action) ||
    (payload !== undefined &&
      payload !== null &&
      (typeof payload !== 'object' || Array.isArray(payload))) ||
    (message !== undefined && (typeof message !== 'string' || message.length > 200)) ||
    (idempotencyKey !== undefined &&
      (typeof idempotencyKey !== 'string' || !idempotencyKey || idempotencyKey.length > 100))
  ) {
    return err(
      c,
      400,
      'action must be ring, lock, lost_mode_on or lost_mode_off; payload.message max 200 chars; idempotency_key max 100 chars',
    )
  }
  const sql = db(c.env)
  if (idempotencyKey) {
    const existing = await sql`
      SELECT id, device_id, action, payload, status, nonce, issued_at, expires_at
      FROM commands WHERE device_id = ${deviceId} AND idempotency_key = ${idempotencyKey}`
    if (existing.length) {
      return c.json(publicCommand(existing[0] as unknown as CommandRow), 200, {
        'Cache-Control': 'no-store',
      })
    }
  }
  // The message only applies to lost_mode_on; other actions carry no payload.
  const storedPayload =
    action === 'lost_mode_on' && typeof message === 'string' ? { message } : null
  const rows = await sql`
    INSERT INTO commands (device_id, action, payload, nonce, idempotency_key, expires_at)
    VALUES (${deviceId}, ${action}, ${storedPayload ? JSON.stringify(storedPayload) : null}::jsonb,
            ${newToken()}, ${idempotencyKey ?? null}, now() + interval '5 minutes')
    RETURNING id, device_id, action, payload, status, nonce, issued_at, expires_at`
  const cmd = rows[0] as unknown as CommandRow
  if (action === 'lost_mode_on') {
    await sql`UPDATE devices SET lost_mode = true, lost_message = ${typeof message === 'string' ? message : null} WHERE id = ${deviceId}`
  } else if (action === 'lost_mode_off') {
    await sql`UPDATE devices SET lost_mode = false WHERE id = ${deviceId}`
  }
  await audit(c.env, auth.user.id, 'command_create', deviceId, {
    action,
    command_id: cmd.id,
  })
  return c.json(publicCommand(cmd), 201, { 'Cache-Control': 'no-store' })
})

// GET /devices/:id/commands — user lists a device's command history.
app.get('/devices/:id/commands', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const limit = clampLimit(c.req.query('limit'), 20, 100)
  const rows = await db(c.env)`
    SELECT id, action, payload, status, issued_at, expires_at, acked_at, result
    FROM commands WHERE device_id = ${deviceId} ORDER BY issued_at DESC LIMIT ${limit}`
  return c.json({ commands: rows })
})

// GET /devices/:id/commands/pending — the device polls for its pending commands.
// Push is never command auth: the device fetches over authenticated TLS, and
// the nonce is for the device's own replay tracking.
app.get('/devices/:id/commands/pending', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  const sql = db(c.env)
  await sql`
    UPDATE commands SET status = 'expired'
    WHERE device_id = ${device.id} AND status = 'pending' AND expires_at <= now()`
  const rows = await sql`
    SELECT id, action, payload, nonce, issued_at, expires_at FROM commands
    WHERE device_id = ${device.id} AND status = 'pending' AND expires_at > now()
    ORDER BY issued_at ASC`
  if (rows.length) {
    const ids = rows.map((r) => r.id as string)
    await sql`UPDATE commands SET status = 'delivered' WHERE id = ANY(${ids})`
  }
  return c.json({ commands: rows })
})

// POST /commands/:id/ack — the device reports a command outcome.
app.post('/commands/:id/ack', async (c) => {
  const body = await jsonBody<{ device_id?: unknown; status?: unknown; result?: unknown }>(c)
  const deviceId = body?.device_id
  const status = body?.status
  const result = body?.result
  if (
    typeof deviceId !== 'string' ||
    !deviceId ||
    (status !== 'acked' && status !== 'failed') ||
    (result !== undefined && (typeof result !== 'string' || result.length > 500))
  ) {
    return err(c, 400, 'device_id and status (acked or failed) required; result max 500 chars')
  }
  const device = await authenticateDevice(c, deviceId)
  if (!device) return err(c, 401, 'unauthorized')
  const commandId = c.req.param('id')
  const sql = db(c.env)
  const rows = await sql`
    UPDATE commands SET status = ${status}, acked_at = now(), result = ${result ?? null}
    WHERE id = ${commandId} AND device_id = ${device.id}
    RETURNING action`
  if (!rows.length) {
    await audit(c.env, device.user_id, 'command_reject', device.id, { command_id: commandId })
    return err(c, 404, 'command not found')
  }
  await audit(c.env, device.user_id, 'command_ack', device.id, {
    action: rows[0].action as string,
    status,
  })
  return c.body(null, 204)
})

type SecurityEventInput = {
  event_type: string
  details?: Record<string, unknown> | null
  confirmed?: boolean
  created_at?: string
}

function validEvent(e: unknown): e is SecurityEventInput {
  if (typeof e !== 'object' || e === null || Array.isArray(e)) return false
  const { event_type, details, confirmed, created_at } = e as Record<string, unknown>
  return (
    typeof event_type === 'string' &&
    (EVENT_TYPES as readonly string[]).includes(event_type) &&
    (details === undefined ||
      details === null ||
      (typeof details === 'object' && !Array.isArray(details))) &&
    (confirmed === undefined || typeof confirmed === 'boolean') &&
    (created_at === undefined ||
      (typeof created_at === 'string' && !Number.isNaN(Date.parse(created_at))))
  )
}

// POST /devices/:id/security-events — the device reports security events in bulk.
app.post('/devices/:id/security-events', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ events?: unknown }>(c)
  const events = body?.events
  if (!Array.isArray(events) || events.length === 0 || events.length > 100) {
    return err(c, 400, 'events must be an array of 1-100 events')
  }
  if (!events.every(validEvent)) {
    return err(
      c,
      400,
      'each event needs a valid event_type; details must be an object, confirmed a boolean, created_at ISO',
    )
  }
  const sql = db(c.env)
  // Single bulk insert: one round trip for the whole batch.
  const now = new Date().toISOString()
  const params: unknown[] = []
  const tuples = events.map((e, i) => {
    const o = i * 5
    params.push(
      device.id,
      e.event_type,
      e.details ? JSON.stringify(e.details) : null,
      e.confirmed ?? false,
      e.created_at ?? now,
    )
    return `($${o + 1}, $${o + 2}, $${o + 3}::jsonb, $${o + 4}, $${o + 5})`
  })
  await sql(
    `INSERT INTO security_events (device_id, event_type, details, confirmed, created_at) VALUES ${tuples.join(',')}`,
    params,
  )
  return c.json({ accepted: events.length }, 201)
})

// GET /devices/:id/security-events — user lists a device's security events.
app.get('/devices/:id/security-events', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const limit = clampLimit(c.req.query('limit'), 50)
  const rows = await db(c.env)`
    SELECT id, event_type, details, confirmed, created_at FROM security_events
    WHERE device_id = ${deviceId} ORDER BY created_at DESC LIMIT ${limit}`
  return c.json({ events: rows })
})

export default app
