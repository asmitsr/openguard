import { Hono } from 'hono'
import { db, type Env } from '../lib/db'
import { authenticateDevice, authenticateUser, ownsDevice } from '../lib/auth'
import { clampLimit, err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

const MAX_BATCH = 500

type LocationInput = {
  latitude: number
  longitude: number
  accuracy: number
  recorded_at: string
  provider?: string | null
}

function validPoint(p: unknown): p is LocationInput {
  if (typeof p !== 'object' || p === null) return false
  const { latitude, longitude, accuracy, recorded_at, provider } = p as Record<string, unknown>
  return (
    typeof latitude === 'number' &&
    Number.isFinite(latitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    typeof longitude === 'number' &&
    Number.isFinite(longitude) &&
    longitude >= -180 &&
    longitude <= 180 &&
    typeof accuracy === 'number' &&
    Number.isFinite(accuracy) &&
    accuracy >= 0 &&
    typeof recorded_at === 'string' &&
    !Number.isNaN(Date.parse(recorded_at)) &&
    (provider === undefined ||
      provider === null ||
      (typeof provider === 'string' && provider.length <= 50))
  )
}

app.post('/:id/locations', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  if (rateLimited(`loc:${device.id}`, 120, 60_000)) return err(c, 429, 'too many requests')
  const body = await jsonBody<{ locations?: unknown }>(c)
  const locations = body?.locations
  if (!Array.isArray(locations) || locations.length === 0 || locations.length > MAX_BATCH) {
    return err(c, 400, `locations must be an array of 1-${MAX_BATCH} points`)
  }
  if (!locations.every(validPoint)) {
    return err(
      c,
      400,
      'each location needs latitude (-90..90), longitude (-180..180), accuracy (>=0) and recorded_at (ISO)',
    )
  }
  const sql = db(c.env)
  // Single bulk insert: one round trip for the whole batch.
  // (Location payloads are never logged.)
  const params: unknown[] = []
  const tuples = locations.map((p, i) => {
    const o = i * 6
    params.push(device.id, p.latitude, p.longitude, p.accuracy, p.recorded_at, p.provider ?? null)
    return `($${o + 1}, $${o + 2}, $${o + 3}, $${o + 4}, $${o + 5}, $${o + 6})`
  })
  await sql(
    `INSERT INTO locations (device_id, latitude, longitude, accuracy, recorded_at, provider) VALUES ${tuples.join(',')}`,
    params,
  )
  await sql`UPDATE devices SET last_seen_at = now() WHERE id = ${device.id}`
  return c.json({ accepted: locations.length }, 201)
})

app.get('/:id/locations', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const limit = clampLimit(c.req.query('limit'))
  const before = c.req.query('before')
  if (before !== undefined && Number.isNaN(Date.parse(before))) {
    return err(c, 400, 'before must be an ISO date')
  }
  const sql = db(c.env)
  const rows = before
    ? await sql`
        SELECT id, latitude, longitude, accuracy, recorded_at, provider FROM locations
        WHERE device_id = ${deviceId} AND recorded_at < ${before}
        ORDER BY recorded_at DESC LIMIT ${limit}`
    : await sql`
        SELECT id, latitude, longitude, accuracy, recorded_at, provider FROM locations
        WHERE device_id = ${deviceId}
        ORDER BY recorded_at DESC LIMIT ${limit}`
  const last = rows[rows.length - 1]
  return c.json({
    locations: rows,
    next_before: rows.length === limit && last ? (last.recorded_at as string) : null,
  })
})

app.get('/:id/latest', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const rows = await db(c.env)`
    SELECT id, latitude, longitude, accuracy, recorded_at, provider FROM locations
    WHERE device_id = ${deviceId} ORDER BY recorded_at DESC LIMIT 1`
  return c.json(rows[0] ?? null)
})

export default app
