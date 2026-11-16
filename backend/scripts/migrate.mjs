// Applies db/schema.sql, then db/migrations/*.sql in version order,
// recording applied versions in schema_migrations. Idempotent: re-running
// skips applied files. Fails loudly (non-zero exit) on any error.
//
// Usage (Docker): docker compose exec api node scripts/migrate.mjs
// Usage (local):  DATABASE_URL=postgres://... node backend/scripts/migrate.mjs
//
// db/ sits next to backend/ in the repo, and the Docker image mirrors that
// layout (/app/db next to /app/backend), so the path below works in both.
// Override with OPENGUARD_DB_DIR if your layout differs.
import postgres from 'postgres'
import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import process from 'node:process'

const DATABASE_URL = process.env.DATABASE_URL
if (!DATABASE_URL) {
  console.error('DATABASE_URL is required')
  process.exit(1)
}

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const dbDir = process.env.OPENGUARD_DB_DIR ?? path.resolve(scriptDir, '..', '..', 'db')

const sql = postgres(DATABASE_URL, { max: 1 })

async function main() {
  await sql`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version text PRIMARY KEY,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`
  const applied = new Set(
    (await sql`SELECT version FROM schema_migrations`).map((r) => r.version),
  )

  const files = [{ version: '000_schema', file: path.join(dbDir, 'schema.sql') }]
  const migrationFiles = (await readdir(path.join(dbDir, 'migrations')))
    .filter((f) => f.endsWith('.sql'))
    .sort()
  for (const f of migrationFiles) {
    files.push({ version: f.replace(/\.sql$/, ''), file: path.join(dbDir, 'migrations', f) })
  }

  for (const { version, file } of files) {
    if (applied.has(version)) {
      console.log(`skip ${version} (already applied)`)
      continue
    }
    const contents = await readFile(file, 'utf8')
    // One transaction per file: the migration and its bookkeeping row land
    // together, so a failure leaves no half-applied version recorded.
    // .simple() is the simple wire protocol, which allows the multi-statement
    // files (including the DO block in 003_recovery.sql).
    await sql.begin(async (tx) => {
      await tx.unsafe(contents).simple()
      await tx`INSERT INTO schema_migrations (version) VALUES (${version})`
    })
    console.log(`applied ${version}`)
  }
  console.log('migrations up to date')
}

try {
  await main()
} catch (e) {
  console.error('migration failed:', e)
  process.exitCode = 1
} finally {
  await sql.end()
}
