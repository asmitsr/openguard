import { Hono } from 'hono'
import { db, type Env } from '../lib/db'
import { authenticateUser, ownsDevice } from '../lib/auth'
import { clampLimit, err } from '../lib/http'

const app = new Hono<{ Bindings: Env }>()

app.get('/audit', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const limit = clampLimit(c.req.query('limit'))
  // detail is returned as a JSON string (detail::text): the dashboard renders
  // it as text, and API consumers can JSON.parse it.
  const rows = await db(c.env)`
    SELECT id, event, device_id, created_at, detail::text AS detail FROM audit_events
    WHERE user_id = ${auth.user.id} ORDER BY created_at DESC LIMIT ${limit}`
  return c.json(rows)
})

app.get('/privacy', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const sql = db(c.env)
  const [locRows, devRows] = await Promise.all([
    sql`SELECT COUNT(*)::int AS n FROM locations l
        JOIN devices d ON d.id = l.device_id WHERE d.user_id = ${auth.user.id}`,
    sql`SELECT COUNT(*)::int AS n FROM devices WHERE user_id = ${auth.user.id}`,
  ])
  return c.json({
    retention_days: 30,
    location_count: (locRows[0] as { n: number }).n,
    device_count: (devRows[0] as { n: number }).n,
    server_data: [
      'account email and password hash',
      'device name, model and OS version',
      'location history (latitude, longitude, accuracy, timestamp, provider)',
      'battery level, charging state and network type',
      'remote command history and security event log',
      'session metadata (user agent, IP address)',
      'audit log of account and device actions',
    ],
    // third_parties are plain strings: the dashboard renders them as a list.
    third_parties: [
      'Cloudflare — Hosts the API (Cloudflare Workers)',
      'Neon — Hosts the PostgreSQL database',
    ],
  })
})

function csvCell(v: string | null): string {
  if (v === null || v === undefined) return ''
  return /[",\n]/.test(v) ? `"${v.replaceAll('"', '""')}"` : v
}

app.get('/devices/:id/export', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const format = c.req.query('format') ?? 'json'
  if (format !== 'json' && format !== 'csv') return err(c, 400, 'format must be csv or json')
  const rows = await db(c.env)`
    SELECT latitude, longitude, accuracy, recorded_at, provider FROM locations
    WHERE device_id = ${deviceId} ORDER BY recorded_at ASC LIMIT 100000`
  const filename = `openguard-${deviceId}-locations.${format}`
  const headers = {
    'Content-Disposition': `attachment; filename="${filename}"`,
  }
  if (format === 'csv') {
    const lines = ['latitude,longitude,accuracy,recorded_at,provider']
    for (const r of rows) {
      lines.push(
        [r.latitude, r.longitude, r.accuracy, r.recorded_at, csvCell(r.provider as string | null)].join(
          ',',
        ),
      )
    }
    return c.text(lines.join('\n'), 200, { ...headers, 'Content-Type': 'text/csv' })
  }
  return c.json(rows, 200, { ...headers, 'Content-Type': 'application/json' })
})

export default app
