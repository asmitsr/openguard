import { neon, type NeonQueryFunction } from '@neondatabase/serverless'

// Worker bindings. DATABASE_URL is set as a Workers secret by the operator;
// it is never hardcoded or logged.
export type Env = {
  DATABASE_URL: string
}

// Rows as objects (arrayMode off, fullResults off).
export type Sql = NeonQueryFunction<false, false>

// One Neon HTTP client per call. The driver is stateless HTTP (no pooling),
// so creating it per request is cheap.
export function db(env: Env): Sql {
  return neon(env.DATABASE_URL)
}
