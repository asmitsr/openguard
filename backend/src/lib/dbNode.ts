import postgres, { PostgresError, type Sql as PgSql } from 'postgres'
import { NeonDbError } from '@neondatabase/serverless'
import { setSqlImpl, type Sql } from './db'

// Node-only driver (self-hosted Docker path). Imported ONLY by server.ts —
// never by the Worker entry — so postgres.js stays out of the Worker bundle.
//
// @neondatabase/serverless speaks SQL-over-HTTP to Neon and cannot talk to a
// plain Postgres container, so the Node path uses postgres.js behind the same
// `Sql` interface. Two translations keep route behaviour identical to Neon:
//
// 1. Result shapes. Neon returns int8 as a JS number and timestamps as raw
//    ISO strings; postgres.js v4 returns int8 as a string and timestamps as
//    Date objects. The Date form would change behaviour routes rely on (CSV
//    export stringifies recorded_at; the locations cursor passes it through
//    as a string). The type overrides below restore Neon's shapes.
// 2. Errors. Routes catch `NeonDbError` with code 23505 (unique violation)
//    to turn races into a 409 / re-link instead of a 500. postgres.js throws
//    PostgresError, so errors are translated (field names differ too:
//    postgres.js uses schema_name/table_name/..., Neon uses schema/table/...).
//    Connection errors are NOT translated: Neon throws a fetch error for
//    those too, not a NeonDbError.

const NEON_COMPAT_TYPES = {
  // int8 (oid 20): bigserial ids (locations.id, audit_events.id,
  // security_events.id). Values are far below 2^53, so Number is exact.
  int8AsNumber: {
    to: 20,
    from: [20],
    parse: (raw: string): number => Number(raw),
  },
  // timestamp (1114) / timestamptz (1184): keep Postgres's raw text output
  // (ISO, e.g. "2026-11-17 12:34:56.789+00"), exactly what Neon returns.
  // The container runs in UTC (postgres:16-alpine default), as does Neon.
  timestampAsText: {
    to: 1184,
    from: [1114, 1184],
    parse: (raw: string): string => raw,
  },
}

function toNeonError(e: unknown): unknown {
  if (!(e instanceof PostgresError)) return e
  const err = new NeonDbError(e.message)
  err.code = e.code
  err.severity = e.severity
  err.detail = e.detail
  err.hint = e.hint
  err.position = e.position
  err.internalPosition = e.internal_position
  err.internalQuery = e.internal_query
  err.where = e.where
  err.schema = e.schema_name
  err.table = e.table_name
  err.column = e.column_name
  err.dataType = e.data_type_name
  err.constraint = e.constraint_name
  err.file = e.file
  err.line = e.line
  err.routine = e.routine
  err.sourceError = e
  return err
}

let pg: PgSql | null = null

// A postgres.js client with the Neon-shaped interface: tagged template plus
// the (queryText, params) form the bulk inserts use (recovery codes,
// security events, locations), which maps to sql.unsafe with $1..$n params.
// Routes use no other member of the Neon query function (no .transaction).
const nodeSql = ((strings: TemplateStringsArray | string, ...args: unknown[]) => {
  if (!pg) throw new Error('Node DB not initialised: call initNodeDb() first')
  const client = pg
  const run = async () => {
    if (typeof strings === 'string') {
      const params = (args[0] as unknown[] | undefined) ?? []
      return client.unsafe(strings, params as never[])
    }
    return client(strings, ...(args as never[]))
  }
  return run().catch((e: unknown) => {
    throw toNeonError(e)
  })
}) as unknown as Sql

export function initNodeDb(connectionString: string): void {
  pg = postgres(connectionString, { types: NEON_COMPAT_TYPES })
  setSqlImpl(() => nodeSql)
}

export async function closeNodeDb(): Promise<void> {
  if (!pg) return
  const client = pg
  pg = null
  await client.end()
}
