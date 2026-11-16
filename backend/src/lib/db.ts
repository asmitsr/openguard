import { neon, type NeonQueryFunction } from '@neondatabase/serverless'

// Worker bindings. DATABASE_URL is set as a Workers secret by the operator;
// it is never hardcoded or logged.
export type Env = {
  DATABASE_URL: string
}

// Rows as objects (arrayMode off, fullResults off).
export type Sql = NeonQueryFunction<false, false>

// Node self-host override. The Worker entry never touches this: on Workers
// db() always returns a Neon client. The Node entry (server.ts) installs a
// postgres.js-backed implementation at startup via setSqlImpl, and dbNode.ts
// is imported only by server.ts — so postgres.js never enters the Worker
// bundle and route code never sees which driver is behind db().
let sqlImpl: ((env: Env) => Sql) | null = null

export function setSqlImpl(impl: (env: Env) => Sql): void {
  sqlImpl = impl
}

// One Neon HTTP client per call. The driver is stateless HTTP (no pooling),
// so creating it per request is cheap. (The Node implementation ignores env
// and returns its shared pooled client.)
export function db(env: Env): Sql {
  return sqlImpl ? sqlImpl(env) : neon(env.DATABASE_URL)
}
