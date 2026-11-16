// API layer — same endpoints, field names and auth contract as the
// original vanilla dashboard. Client-side KDF: the server never sees the
// password. The client derives a 32-byte key with PBKDF2-SHA256 (600k
// iterations) from password + salt and sends only the hex derived key.

export interface Device {
  id: string
  name: string
  model?: string | null
  os_version?: string | null
  lost_mode?: boolean
  last_seen_at?: string | null
  battery_level?: number | null
  battery_charging?: boolean | null
  network_type?: string | null
  location_permission?: string | null
  location_enabled?: boolean | null
  _latest?: Location | null
}

export interface Location {
  id?: string
  latitude: number
  longitude: number
  accuracy?: number | null
  recorded_at: string
  provider?: string | null
}

export interface Command {
  id: string
  action: string
  status: string
  issued_at: string
  result?: string | null
}

export interface CommandDetail {
  command: Command & {
    device_id: string
    payload?: unknown
    nonce_truncated: string
    expires_at: string
    acked_at?: string | null
  }
  audit: { event: string; created_at: string; detail?: string | null }[]
}

export interface SecurityEvent {
  event_type: string
  created_at: string
  confirmed?: boolean
}

export interface Session {
  id: string
  user_agent?: string | null
  ip?: string | null
  created_at: string
  last_used_at: string
  current?: boolean
}

export interface AuditEvent {
  event: string
  created_at: string
  detail?: string | null
}

export interface PrivacyInfo {
  retention_days: number
  location_count: number
  device_count: number
  server_data: string[]
  third_parties: string[]
}

let access: string | null = localStorage.getItem('openguard_access')
let refresh: string | null = localStorage.getItem('openguard_refresh')

export const hasToken = () => !!access
export const getEmail = () => localStorage.getItem('openguard_email')

export function setTokens(a: string, r: string, email: string) {
  access = a
  refresh = r
  localStorage.setItem('openguard_access', a)
  localStorage.setItem('openguard_refresh', r)
  localStorage.setItem('openguard_email', email)
}

export function clearTokens() {
  access = null
  refresh = null
  localStorage.removeItem('openguard_access')
  localStorage.removeItem('openguard_refresh')
  localStorage.removeItem('openguard_email')
}

// Set by App: called when the refresh token fails and the user must log in again.
export let onSessionExpired: (() => void) | null = null
export function setOnSessionExpired(fn: () => void) {
  onSessionExpired = fn
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('')
}

function unhex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16)
  return out
}

export function randomSalt(): string {
  return hex(crypto.getRandomValues(new Uint8Array(16)))
}

export async function deriveKey(password: string, saltHex: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: unhex(saltHex) as BufferSource, iterations: 600000, hash: 'SHA-256' },
    key,
    256,
  )
  return hex(new Uint8Array(bits))
}

interface ApiOpts extends Omit<RequestInit, 'body'> {
  body?: unknown
  _retried?: boolean
}

export async function api<T>(path: string, opts: ApiOpts = {}): Promise<T> {
  const headers: Record<string, string> = { ...(opts.headers as Record<string, string>) }
  if (access) headers['Authorization'] = 'Bearer ' + access
  let body: BodyInit | undefined
  if (opts.body && typeof opts.body === 'object') {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.body)
  }
  const res = await fetch('/api/v1' + path, { ...opts, headers, body })
  if (res.status === 401 && refresh && !opts._retried) {
    const r = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refresh }),
    })
    if (r.ok) {
      const t = (await r.json()) as { access_token: string; refresh_token: string }
      access = t.access_token
      refresh = t.refresh_token
      localStorage.setItem('openguard_access', access)
      localStorage.setItem('openguard_refresh', refresh)
      return api(path, { ...opts, _retried: true })
    }
    clearTokens()
    onSessionExpired?.()
    throw new Error('Session expired — please log in again.')
  }
  if (!res.ok) {
    let msg = 'Request failed (' + res.status + ')'
    try {
      const e = (await res.json()) as { error?: string }
      if (e.error) msg = e.error
    } catch (_) {}
    throw new Error(msg)
  }
  if (res.status === 204) return null as T
  return res.json() as Promise<T>
}

// Step-up auth: re-derive the key from the password and exchange it for a
// short-lived token used by erase and recovery-code generation.
export async function stepUp(password: string): Promise<string> {
  const email = getEmail()
  if (!email) throw new Error('Could not find your email — please log in again.')
  const s = await api<{ salt: string }>('/auth/salt?email=' + encodeURIComponent(email))
  const step = await api<{ step_up_token: string }>('/auth/step-up', {
    method: 'POST',
    body: { derived_key: await deriveKey(password, s.salt) },
  })
  return step.step_up_token
}

// Raw fetch (no refresh retry), as in the original dashboard.
export async function exportData(deviceId: string, format: 'csv' | 'json') {
  const res = await fetch('/api/v1/devices/' + deviceId + '/export?format=' + format, {
    headers: { Authorization: 'Bearer ' + access },
  })
  if (!res.ok) throw new Error('Export failed (' + res.status + ')')
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'openguard-' + deviceId + '.' + format
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 5000)
}
