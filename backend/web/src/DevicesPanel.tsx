import { useRef, useState } from 'react'
import { api, type Device } from './api'
import { relTime } from './util'

// Device health (heartbeat): true when the device reported any health
// field. Older app versions report none, so the block stays hidden.
function hasHealth(d: Device): boolean {
  return (
    d.storage_total_bytes != null ||
    d.ram_total_bytes != null ||
    d.battery_temperature_c != null ||
    d.booted_at != null ||
    d.notifications_enabled != null
  )
}

/** Bytes as "12.4 GB" / "850 MB", one decimal, no trailing ".0". */
function fmtBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = bytes
  let u = 0
  while (v >= 1024 && u < units.length - 1) {
    v /= 1024
    u++
  }
  return v.toFixed(1).replace(/\.0$/, '') + ' ' + units[u]
}

export default function DevicesPanel({
  devices,
  selected,
  listErr,
  onSelect,
  onChanged,
}: {
  devices: Device[] | null
  selected: string | null
  listErr: string
  onSelect: (id: string) => void
  onChanged: () => void // reload devices + privacy (after add/unlink)
}) {
  const [name, setName] = useState('')
  const [model, setModel] = useState('')
  const [os, setOs] = useState('')
  const [err, setErr] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [armedSignOut, setArmedSignOut] = useState<string | null>(null)
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  async function addDevice() {
    const n = name.trim()
    if (!n) {
      setErr('Give the device a name.')
      return
    }
    try {
      const d = await api<{ device_token: string }>('/devices', {
        method: 'POST',
        body: { name: n, model: model.trim(), os_version: os.trim() },
      })
      setErr('')
      setToken(d.device_token)
      setName('')
      setModel('')
      setOs('')
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function unlink(id: string) {
    if (!confirm('Unlink this device? Its location history will be deleted too.')) return
    try {
      await api('/devices/' + id, { method: 'DELETE' })
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  // Two-click inline confirm (same pattern as delete-account): first click
  // arms the link, second click within 10 s signs the device out.
  async function signOut(id: string) {
    if (armedSignOut !== id) {
      setArmedSignOut(id)
      if (armTimer.current) clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => setArmedSignOut(null), 10000)
      return
    }
    if (armTimer.current) clearTimeout(armTimer.current)
    setArmedSignOut(null)
    try {
      await api('/devices/' + id + '/sign-out', { method: 'POST' })
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <section className="card">
      <h2>Devices</h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : devices === null ? (
          <p className="muted">Loading…</p>
        ) : devices.length === 0 ? (
          <p className="muted">No devices yet. Add one below, then paste the token into the Android app.</p>
        ) : (
          devices.map((d) => (
            <div key={d.id} className={'dev' + (d.id === selected ? ' sel' : '')} onClick={() => onSelect(d.id)}>
              <b>{d.name}</b>
              {d.lost_mode && <span className="badge">LOST MODE</span>}
              <span className="muted">
                {d.model || ''}
                {d.os_version ? ' · ' + d.os_version : ''}
              </span>
              <br />
              <span className="muted">
                Last seen {relTime(d.last_seen_at)}
                {d.battery_level != null ? ' · Battery ' + d.battery_level + '%' + (d.battery_charging ? ' (charging)' : '') : ''}
                {d.network_type ? ' · ' + d.network_type : ''}
                {d.location_permission ? ' · Location permission: ' + d.location_permission : ''}
                {d.location_enabled != null ? ' · Location ' + (d.location_enabled ? 'on' : 'off') : ''}
              </span>
              {d.id === selected && hasHealth(d) && (
                <>
                  <br />
                  <span className="muted">
                    {d.storage_free_bytes != null && d.storage_total_bytes != null
                      ? 'Storage ' + fmtBytes(d.storage_free_bytes) + ' free of ' + fmtBytes(d.storage_total_bytes)
                      : ''}
                    {d.ram_free_bytes != null && d.ram_total_bytes != null
                      ? ' · RAM ' + fmtBytes(d.ram_free_bytes) + ' free of ' + fmtBytes(d.ram_total_bytes)
                      : ''}
                    {d.battery_temperature_c != null ? ' · Battery temp ' + d.battery_temperature_c.toFixed(1) + '°C' : ''}
                    {d.booted_at ? ' · Last boot ' + relTime(d.booted_at) : ''}
                    {d.notifications_enabled != null
                      ? ' · Notifications ' + (d.notifications_enabled ? 'allowed' : 'blocked')
                      : ''}
                  </span>
                </>
              )}
              <br />
              <button
                className="link"
                onClick={(e) => {
                  e.stopPropagation()
                  unlink(d.id)
                }}
              >
                Unlink
              </button>{' '}
              <button
                className="link"
                onClick={(e) => {
                  e.stopPropagation()
                  signOut(d.id)
                }}
              >
                {armedSignOut === d.id
                  ? 'Click again to sign out — keeps the device and its history, but kills its token and your other sessions'
                  : 'Sign out'}
              </button>
            </div>
          ))
        )}
      </div>
      <h3>Add device</h3>
      <div className="row">
        <input placeholder="Name (e.g. My phone)" value={name} onChange={(e) => setName(e.target.value)} />
        <input placeholder="Model (e.g. Infinix Hot 60 Pro+)" value={model} onChange={(e) => setModel(e.target.value)} />
        <input placeholder="OS version (e.g. Android 15)" value={os} onChange={(e) => setOs(e.target.value)} />
      </div>
      <button onClick={addDevice}>Add device</button>
      <p className="err">{err}</p>
      {token && (
        <div id="token-box">
          <b>Device token — shown once only.</b>
          <br />
          Paste this into the OpenGuard Android app:
          <br />
          <code>{token}</code>
        </div>
      )}
    </section>
  )
}
