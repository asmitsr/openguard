import { Hono } from 'hono'
import { db, type Env } from '../lib/db'
import { authenticateDevice, authenticateUser, ownsDevice } from '../lib/auth'
import { audit } from '../lib/audit'
import { clampLimit, err, jsonBody } from '../lib/http'
import { rateLimited } from '../lib/ratelimit'

const app = new Hono<{ Bindings: Env }>()

// Evidence metadata only — clip bytes never pass through this Worker
// (Workers Free allows only 10 ms CPU/request, and Neon Free is 1 GB).
// Bytes go from the phone straight to the owner's own Google Drive;
// this table holds the metadata + Drive link for the dashboard.

const CAMERAS = ['front', 'back', 'audio'] as const
const STORAGES = ['local', 'drive'] as const
const MAX_CLIP_BYTES = 200_000_000

type EvidenceInput = {
  camera: string
  recorded_at: string
  duration_s: number
  size_bytes: number
  storage: string
  drive_file_id?: string | null
  drive_link?: string | null
  uploaded_at?: string | null
}

function isIso(s: unknown): s is string {
  return typeof s === 'string' && !Number.isNaN(Date.parse(s))
}

function validClip(b: unknown): b is EvidenceInput {
  if (typeof b !== 'object' || b === null) return false
  const o = b as Record<string, unknown>
  const cameraOk = typeof o.camera === 'string' && (CAMERAS as readonly string[]).includes(o.camera)
  const storageOk = typeof o.storage === 'string' && (STORAGES as readonly string[]).includes(o.storage)
  const durationOk =
    typeof o.duration_s === 'number' &&
    Number.isInteger(o.duration_s) &&
    o.duration_s >= 1 &&
    o.duration_s <= 60
  const sizeOk =
    typeof o.size_bytes === 'number' &&
    Number.isInteger(o.size_bytes) &&
    o.size_bytes >= 0 &&
    o.size_bytes <= MAX_CLIP_BYTES
  const driveIdOk =
    o.drive_file_id === undefined ||
    o.drive_file_id === null ||
    (typeof o.drive_file_id === 'string' && o.drive_file_id.length >= 1 && o.drive_file_id.length <= 100)
  const driveLinkOk =
    o.drive_link === undefined ||
    o.drive_link === null ||
    (typeof o.drive_link === 'string' &&
      o.drive_link.startsWith('https://drive.google.com/') &&
      o.drive_link.length <= 500)
  const uploadedOk = o.uploaded_at === undefined || o.uploaded_at === null || isIso(o.uploaded_at)
  if (!cameraOk || !storageOk || !durationOk || !sizeOk || !driveIdOk || !driveLinkOk || !uploadedOk) {
    return false
  }
  if (!isIso(o.recorded_at)) return false
  // A drive clip must carry its Drive reference; a local clip must not.
  if (o.storage === 'drive') {
    return typeof o.drive_file_id === 'string' && typeof o.drive_link === 'string'
  }
  return o.drive_file_id == null && o.drive_link == null
}

// POST /devices/:id/evidence — device posts one clip's metadata.
// Device-token auth. Audited like commands (evidence_upload).
app.post('/:id/evidence', async (c) => {
  const device = await authenticateDevice(c, c.req.param('id'))
  if (!device) return err(c, 401, 'unauthorized')
  if (rateLimited(`ev:${device.id}`, 30, 60_000)) return err(c, 429, 'too many requests')
  const body = await jsonBody(c)
  if (!validClip(body)) {
    return err(
      c,
      400,
      'clip needs camera (front/back/audio), recorded_at (ISO), duration_s (1-60), size_bytes, storage (local/drive); drive clips need drive_file_id + drive_link',
    )
  }
  const sql = db(c.env)
  const uploadedAt =
    body.storage === 'drive' ? (body.uploaded_at ?? new Date().toISOString()) : null
  const rows = await sql`
    INSERT INTO evidence_clips
      (device_id, recorded_at, camera, duration_s, size_bytes, storage, drive_file_id, drive_link, uploaded_at)
    VALUES (${device.id}, ${body.recorded_at}, ${body.camera}, ${body.duration_s},
            ${body.size_bytes}, ${body.storage}, ${body.drive_file_id ?? null},
            ${body.drive_link ?? null}, ${uploadedAt})
    RETURNING id, device_id, recorded_at, camera, duration_s, size_bytes,
              storage, drive_file_id, drive_link, uploaded_at, created_at`
  const clip = rows[0] as unknown as Record<string, unknown>
  await audit(c.env, device.user_id, 'evidence_upload', device.id, {
    clip_id: clip.id as string,
    camera: body.camera,
    storage: body.storage,
  })
  return c.json({ clip }, 201)
})

// GET /devices/:id/evidence — owner lists one device's clips, newest first.
app.get('/:id/evidence', async (c) => {
  const auth = await authenticateUser(c)
  if (!auth) return err(c, 401, 'unauthorized')
  const deviceId = c.req.param('id')
  if (!(await ownsDevice(c.env, auth.user.id, deviceId))) return err(c, 404, 'device not found')
  const limit = clampLimit(c.req.query('limit'), 50, 100)
  const rows = await db(c.env)`
    SELECT id, device_id, recorded_at, camera, duration_s, size_bytes,
           storage, drive_file_id, drive_link, uploaded_at, created_at
    FROM evidence_clips WHERE device_id = ${deviceId}
    ORDER BY recorded_at DESC LIMIT ${limit}`
  return c.json({ clips: rows })
})

export default app
