import type { Context } from 'hono'
import type { ContentfulStatusCode } from 'hono/utils/http-status'

// Parses a JSON body, returning null on empty/invalid JSON instead of throwing.
export async function jsonBody<T>(c: Context<any>): Promise<T | null> {
  try {
    return (await c.req.json()) as T
  } catch {
    return null
  }
}

export function err(c: Context<any>, status: ContentfulStatusCode, message: string) {
  return c.json({ error: message }, status)
}

// Parses a ?limit= query param: default 100, clamped to 1..500.
export function clampLimit(raw: string | undefined, def = 100, max = 500): number {
  const n = raw === undefined ? def : Number.parseInt(raw, 10)
  if (!Number.isInteger(n) || n < 1) return def
  return Math.min(n, max)
}
