import { Hono } from 'hono'
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
  const body = await jsonBody<{ name?: unknown; model?: unknown; os_version?: unknown }>(c)
  const name = body?.name
  const model = body?.model
  const osVersion = body?.os_version
  if (
    typeof name !== 'string' ||
    !name.trim() ||
    name.length > 100 ||
    !isOptString(model, 100) ||
    !isOptString(osVersion, 100)
  ) {
    return err(c, 400, 'name (1-100 chars) required; model and os_version max 100 chars')
  }
  const deviceToken = newToken()
  const rows = await db(c.env)`
    INSERT INTO devices (user_id, name, model, os_version, token_hash)
    VALUES (${auth.user.id}, ${name.trim()}, ${model ?? null}, ${osVersion ?? null},
            ${await sha256Hex(deviceToken)})
    RETURNING id, name, model, os_version, last_seen_at, battery_level,
              battery_charging, network_type, created_at`
  const device = rows[0]
  await audit(c.env, auth.user.id, 'device_register', device.id as string, { name: name.trim() })
  // device_token is shown once — only its hash is stored.
  return c.json({ device, device_token: deviceToken }, 201)
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
