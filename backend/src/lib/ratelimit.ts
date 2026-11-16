// Simple in-memory per-key sliding-window rate limit.
// NOTE: state lives in the isolate's memory, so limits apply per isolate, not
// globally — a burst spread across isolates can exceed the nominal limit.
// Good enough for MVP abuse dampening; use Workers KV or Durable Objects for
// exact global limits later.

const hits = new Map<string, number[]>()

export function rateLimited(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now()
  const recent = (hits.get(key) ?? []).filter((t) => now - t < windowMs)
  recent.push(now)
  hits.set(key, recent)
  // Occasional sweep so the map can't grow without bound.
  if (hits.size > 5000 && Math.random() < 0.05) {
    for (const [k, v] of hits) if (v.every((t) => now - t >= windowMs)) hits.delete(k)
  }
  return recent.length > limit
}
