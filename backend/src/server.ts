import process from 'node:process'
import { serve } from '@hono/node-server'
import app from './index'
import { closeNodeDb, initNodeDb } from './lib/dbNode'

// Node entry point for the self-hosted Docker path. The Cloudflare Workers
// entry is src/index.ts; both serve the same Hono app, and the Worker entry
// never imports this file (or dbNode.ts), so postgres.js and
// @hono/node-server never enter the Worker bundle.
//
// Env: DATABASE_URL is required (the only REQUIRED env var the app reads).
// PORT defaults to 8787 — the same port `wrangler dev` uses, so one
// API_BASE_URL works for both paths. RP_ID / RP_NAME / RP_ORIGIN are the
// optional WebAuthn relying-party config for passkeys; when unset, the
// passkeys routes fall back to the Workers deployment values.

const DATABASE_URL = process.env.DATABASE_URL
if (!DATABASE_URL) {
  console.error(
    'DATABASE_URL is required, e.g. postgres://openguard:secret@localhost:5432/openguard',
  )
  process.exit(1)
}
const PORT = Number(process.env.PORT ?? 8787) || 8787

initNodeDb(DATABASE_URL)

const server = serve(
  {
    // Hono bindings come from the 2nd arg of app.fetch; there is no Workers
    // env on Node, so DATABASE_URL is passed explicitly, along with the
    // optional WebAuthn RP config (undefined values fall back to the
    // Workers deployment defaults in the passkeys routes).
    fetch: (req) =>
      app.fetch(req, {
        DATABASE_URL,
        RP_ID: process.env.RP_ID,
        RP_NAME: process.env.RP_NAME,
        RP_ORIGIN: process.env.RP_ORIGIN,
      }),
    port: PORT,
  },
  (info) => {
    console.log(`OpenGuard API listening on http://localhost:${info.port}`)
  },
)

async function shutdown(signal: string): Promise<void> {
  console.log(`\n${signal} received, shutting down…`)
  server.close()
  await closeNodeDb()
  process.exit(0)
}

process.on('SIGINT', () => void shutdown('SIGINT'))
process.on('SIGTERM', () => void shutdown('SIGTERM'))
