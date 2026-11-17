import { Hono } from 'hono'
import { db, type Env } from '../lib/db'
import { authenticateDevice, authenticateUser, ownsDevice } from '../lib/auth'
import { audit } from '../lib/audit'
import { clampLimit, err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

// Geofences: a circular boundary per device. The owner creates them; the
// phone watches its own position and reports enter/exit transitions.

const ACTIONS = ['notify', 'lock', 'lost_mode'] as const
const TRANSITIONS = ['enter', 'exit'] as const

const isName = (v: unknown): v is string =>
  typeof v === 'string' && v.trim().length >= 1 && v.trim().length <= 100
const isLat = (v: unknown): v is number =>
  typeof v === 'number' && Number.isFinite(v) && v >= -90 && v <= 90
const isLon = (v: unknown): v is number =>
  typeof v === 'number' && Number.isFinite(v) && v >= -180 && v <= 180
const isRadius = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 50 && v <= 5000
const isAction = (v: unknown): v is string =>
  typeof v === 'string' && (ACTIONS as readonly string[]).includes(v)
const isIso = (v: unknown): v is string => typeof v === 'string' && !Number.isNaN(Date.parse(v))

// GET /devices/:id/geofences — owner lists one device's geofences.
app.get('/:id/geofences', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const rows = await db(c.env)`
    SELECT id, device_id, name, latitude, longitude, radius_m, action, created_at
    FROM geofences WHERE device_id = ${deviceId}
    ORDER BY created_at ASC`
  return c.json({ geofences: rows })
})

// POST /devices/:id/geofences — owner creates a geofence for a device.
app.post('/:id/geofences', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const body = await jsonBody<{
    name?: unknown
    latitude?: unknown
    longitude?: unknown
    radius_m?: unknown
    action?: unknown
  }>(c)
  const { name, latitude, longitude, radius_m, action } = body ?? {}
  if (!isName(name) || !isLat(latitude) || !isLon(longitude) || !isRadius(radius_m) || !isAction(action)) {
    return err(
      c,
      400,
      'name (1-100 chars), latitude (-90..90), longitude (-180..180), radius_m (50-5000), action (notify/lock/lost_mode) required',
    )
  }
  const trimmedName = name.trim()
  const rows = await db(c.env)`
    INSERT INTO geofences (device_id, name, latitude, longitude, radius_m, action)
    VALUES (${deviceId}, ${trimmedName}, ${latitude}, ${longitude}, ${radius_m}, ${action})
    RETURNING id, device_id, name, latitude, longitude, radius_m, action, created_at`
  const geofence = rows[0]
  await audit(c.env, auth.user.id, 'geofence_create', deviceId, {
    geofence_id: geofence.id as string,
    name: trimmedName,
    action,
  })
  return c.json({ geofence }, 201)
})

// PUT /devices/:id/geofences/:gid — owner updates any subset of fields.
// Absent fields leave the stored values untouched (COALESCE, like heartbeat).
app.put('/:id/geofences/:gid', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const body = await jsonBody<{
    name?: unknown
    latitude?: unknown
    longitude?: unknown
    radius_m?: unknown
    action?: unknown
  }>(c)
  const { name, latitude, longitude, radius_m, action } = body ?? {}
  const hasField =
    name !== undefined || latitude !== undefined || longitude !== undefined ||
    radius_m !== undefined || action !== undefined
  if (
    !hasField ||
    (name !== undefined && !isName(name)) ||
    (latitude !== undefined && !isLat(latitude)) ||
    (longitude !== undefined && !isLon(longitude)) ||
    (radius_m !== undefined && !isRadius(radius_m)) ||
    (action !== undefined && !isAction(action))
  ) {
    return err(
      c,
      400,
      'at least one of name (1-100 chars), latitude (-90..90), longitude (-180..180), radius_m (50-5000), action (notify/lock/lost_mode) required, each in range when present',
    )
  }
  const trimmedName = typeof name === 'string' ? name.trim() : null
  const rows = await db(c.env)`
    UPDATE geofences
    SET name = COALESCE(${trimmedName}, name),
        latitude = COALESCE(${latitude ?? null}, latitude),
        longitude = COALESCE(${longitude ?? null}, longitude),
        radius_m = COALESCE(${radius_m ?? null}, radius_m),
        action = COALESCE(${action ?? null}, action)
    WHERE id = ${c.req.param('gid')} AND device_id = ${deviceId}
    RETURNING id, device_id, name, latitude, longitude, radius_m, action, created_at`
  if (!rows.length) return err(c, 404, 'geofence not found')
  const geofence = rows[0]
  await audit(c.env, auth.user.id, 'geofence_update', deviceId, {
    geofence_id: geofence.id as string,
    name: geofence.name as string,
  })
  return c.json({ geofence })
})

// DELETE /devices/:id/geofences/:gid — owner deletes a geofence.
app.delete('/:id/geofences/:gid', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const rows = await db(c.env)`
    DELETE FROM geofences WHERE id = ${c.req.param('gid')} AND device_id = ${deviceId}
    RETURNING id, name`
  if (!rows.length) return err(c, 404, 'geofence not found')
  await audit(c.env, auth.user.id, 'geofence_delete', deviceId, {
    geofence_id: rows[0].id as string,
    name: rows[0].name as string,
  })
  return c.body(null, 204)
})

// POST /devices/:id/geofences/:gid/events — device reports an enter/exit.
// Device-token auth. Audited like evidence (geofence_event).
app.post('/:id/geofences/:gid/events', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  if (rateLimited(`gf:${device.id}`, 30, 60_000)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ transition?: unknown; recorded_at?: unknown }>(c)
  const { transition, recorded_at } = body ?? {}
  if (
    typeof transition !== 'string' ||
    !(TRANSITIONS as readonly string[]).includes(transition) ||
    !isIso(recorded_at)
  ) {
    return err(c, 400, 'transition (enter/exit) and recorded_at (ISO) required')
  }
  const sql = db(c.env)
  const fences = await sql`
    SELECT id, name FROM geofences
    WHERE id = ${c.req.param('gid')} AND device_id = ${device.id}`
  if (!fences.length) return err(c, 404, 'geofence not found')
  const rows = await sql`
    INSERT INTO geofence_events (geofence_id, device_id, transition, recorded_at)
    VALUES (${fences[0].id as string}, ${device.id}, ${transition}, ${recorded_at})
    RETURNING id, geofence_id, device_id, transition, recorded_at, created_at`
  const event = { ...rows[0], geofence_name: fences[0].name as string }
  await audit(c.env, device.user_id, 'geofence_event', device.id, {
    geofence_id: fences[0].id as string,
    transition,
  })
  return c.json({ event }, 201)
})

// GET /devices/:id/geofences/events — owner lists a device's transitions,
// newest first, with each event's geofence name.
app.get('/:id/geofences/events', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const limit = clampLimit(c.req.query('limit'), 50, 100)
  const rows = await db(c.env)`
    SELECT geofence_events.id, geofence_events.geofence_id, geofences.name AS geofence_name,
           geofence_events.device_id, geofence_events.transition,
           geofence_events.recorded_at, geofence_events.created_at
    FROM geofence_events JOIN geofences ON geofences.id = geofence_events.geofence_id
    WHERE geofence_events.device_id = ${deviceId}
    ORDER BY geofence_events.recorded_at DESC LIMIT ${limit}`
  return c.json({ events: rows })
})

export default app
