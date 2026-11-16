import { Hono } from 'hono'
import { NeonDbError } from '@neondatabase/serverless'
import { db, type Env } from '../lib/db'
import { authenticateDevice, authenticateUser, newToken, sha256Hex } from '../lib/auth'
import { audit } from '../lib/audit'
import { err, jsonBody } from '../lib/http'

const app = new Hono<{ Bindings: Env }>()

function isOptString(v: unknown, max: number): v is string | undefined {
  return v === undefined || v === null || (typeof v === 'string' && v.length <= max)
}

app.get('/', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    SELECT id, name, model, os_version, last_seen_at, battery_level,
           battery_charging, network_type, lost_mode, lost_message,
           location_permission, location_enabled, created_at
    FROM devices WHERE user_id = ${auth.user.id} ORDER BY created_at DESC`
  return c.json(rows)
})

app.post('/', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{
    name?: unknown
    model?: unknown
    os_version?: unknown
    hardware_id?: unknown
  }>(c)
  const name = body?.name
  const model = body?.model
  const osVersion = body?.os_version
  const hardwareId = body?.hardware_id
  if (
    typeof name !== 'string' ||
    !name.trim() ||
    name.length > 100 ||
    !isOptString(model, 100) ||
    !isOptString(osVersion, 100) ||
    !isOptString(hardwareId, 128)
  ) {
    return err(
      c,
      400,
      'name (1-100 chars) required; model and os_version max 100 chars; hardware_id max 128 chars',
    )
  }
  const sql = db(c.env)
  const trimmedName = name.trim()
  const hwId = typeof hardwareId === 'string' && hardwareId ? hardwareId : null
  // Re-link: a reinstall sends the same hardware_id (ANDROID_ID). Return
  // the EXISTING row (history preserved) with a fresh device token instead
  // of duplicating the phone. The token is shown once, as on register.
  const relink = async (id: string) => {
    const token = newToken()
    const rows = await sql`
      UPDATE devices
      SET name = ${trimmedName}, model = ${model ?? null},
          os_version = ${osVersion ?? null}, token_hash = ${await sha256Hex(token)}
      WHERE id = ${id}
      RETURNING id, name, model, os_version, last_seen_at, battery_level,
                battery_charging, network_type, created_at`
    await audit(c.env, auth.user.id, 'device_relink', id, { name: trimmedName })
    return c.json({ device: rows[0], device_token: token }, 200)
  }
  if (hwId) {
    const existing = await sql`
      SELECT id FROM devices
      WHERE user_id = ${auth.user.id} AND hardware_id = ${hwId}`
    if (existing.length) return relink(existing[0].id as string)
  }
  const deviceToken = newToken()
  try {
    const rows = await sql`
      INSERT INTO devices (user_id, name, model, os_version, token_hash, hardware_id)
      VALUES (${auth.user.id}, ${trimmedName}, ${model ?? null}, ${osVersion ?? null},
              ${await sha256Hex(deviceToken)}, ${hwId})
      RETURNING id, name, model, os_version, last_seen_at, battery_level,
                battery_charging, network_type, created_at`
    const device = rows[0]
    await audit(c.env, auth.user.id, 'device_register', device.id as string, { name: trimmedName })
    // device_token is shown once — only its hash is stored.
    return c.json({ device, device_token: deviceToken }, 201)
  } catch (e) {
    // Two concurrent registers with the same hardware_id: the second
    // hits the unique index (23505). Re-link instead of erroring.
    if (e instanceof NeonDbError && e.code === '23505' && hwId) {
      const existing = await sql`
        SELECT id FROM devices
        WHERE user_id = ${auth.user.id} AND hardware_id = ${hwId}`
      if (existing.length) return relink(existing[0].id as string)
    }
    throw e
  }
})

app.delete('/:id', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const rows = await db(c.env)`
    DELETE FROM devices WHERE id = ${c.req.param('id')} AND user_id = ${auth.user.id}
    RETURNING id, name`
  if (!rows.length) return err(c, 404, 'device not found')
  await audit(c.env, auth.user.id, 'device_unlink', rows[0].id as string, {
    name: rows[0].name as string,
  })
  return c.body(null, 204)
})

// Signs a device out: its token is rotated to a random value nobody knows,
// so the app on it can no longer authenticate and the device must be
// unlinked + re-added to pair again. The device row and its history stay.
// Also kills the user's other sessions — a lost phone's app session dies too.
app.post('/:id/sign-out', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  const rows = await db(c.env)`
    UPDATE devices SET token_hash = ${await sha256Hex(newToken())}, push_topic = NULL
    WHERE id = ${deviceId} AND user_id = ${auth.user.id} RETURNING id`
  if (!rows.length) return err(c, 404, 'device not found')
  await db(c.env)`
    DELETE FROM sessions WHERE user_id = ${auth.user.id} AND id != ${auth.sessionId}`
  await audit(c.env, auth.user.id, 'device_sign_out', deviceId)
  return c.body(null, 204)
})

// Registers or rotates a device's push (ntfy) topic. Device token: the
// device generates its own random unguessable topic and tells the server
// where to publish wake-ups. The topic is never returned by any endpoint.
app.post('/:id/push-topic', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{ push_topic?: unknown }>(c)
  const topic = body?.push_topic
  if (
    typeof topic !== 'string' ||
    topic.length < 16 ||
    topic.length > 128 ||
    !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(topic)
  ) {
    return err(c, 400, 'push_topic must be 16-128 chars: letters, digits, dot, dash, underscore')
  }
  try {
    await db(c.env)`UPDATE devices SET push_topic = ${topic} WHERE id = ${device.id}`
  } catch (e) {
    // Topic already held by another device row (stale row after a
    // failed sign-out): the client rotates and retries once.
    if (e instanceof NeonDbError && e.code === '23505') {
      return err(c, 409, 'push_topic already in use — rotate and retry')
    }
    throw e
  }
  return c.body(null, 204)
})

app.post('/:id/heartbeat', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  const body = await jsonBody<{
    battery_level?: unknown
    battery_charging?: unknown
    network_type?: unknown
    location_permission?: unknown
    location_enabled?: unknown
  }>(c)
  const batteryLevel = body?.battery_level
  const batteryCharging = body?.battery_charging
  const networkType = body?.network_type
  const locationPermission = body?.location_permission
  const locationEnabled = body?.location_enabled
  if (
    typeof batteryLevel !== 'number' ||
    !Number.isInteger(batteryLevel) ||
    batteryLevel < 0 ||
    batteryLevel > 100 ||
    typeof batteryCharging !== 'boolean' ||
    !isOptString(networkType, 50) ||
    !isOptString(locationPermission, 50) ||
    (locationEnabled !== undefined &&
      locationEnabled !== null &&
      typeof locationEnabled !== 'boolean')
  ) {
    return err(c, 400, 'battery_level (0-100 int), battery_charging and network_type required')
  }
  await db(c.env)`
    UPDATE devices
    SET battery_level = ${batteryLevel}, battery_charging = ${batteryCharging},
        network_type = ${networkType ?? null}, last_seen_at = now(),
        location_permission = COALESCE(${locationPermission ?? null}, location_permission),
        location_enabled = COALESCE(${locationEnabled ?? null}, location_enabled)
    WHERE id = ${device.id}`
  return c.body(null, 204)
})

export default app
