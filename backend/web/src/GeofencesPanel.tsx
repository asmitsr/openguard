import { useRef, useState } from 'react'
import { api, type Device, type Geofence, type GeofenceEvent } from './api'
import { fmtTime, relTime } from './util'

const ACTION_LABELS: Record<Geofence['action'], string> = {
  notify: 'Notify only',
  lock: 'Lock',
  lost_mode: 'Lost Mode',
}

const ACTION_HELP: Record<Geofence['action'], string> = {
  notify: 'Just an alert when the phone enters or leaves.',
  lock: 'Locks the phone when it leaves the zone.',
  lost_mode: 'Turns on Lost Mode when it leaves the zone.',
}

// Geofences: named circles around places that matter. When the phone
// crosses a boundary, the server records an event and runs the fence's
// action. The list refreshes every 30 seconds — near-live, not real-time.
export default function GeofencesPanel({
  device,
  geofences,
  events,
  listErr,
  onChanged,
}: {
  device: Device | null
  geofences: Geofence[]
  events: GeofenceEvent[]
  listErr: string
  onChanged: () => void // reload fences + events (after a create/edit/delete)
}) {
  const [name, setName] = useState('')
  const [lat, setLat] = useState('')
  const [lng, setLng] = useState('')
  const [radius, setRadius] = useState(500)
  const [action, setAction] = useState<Geofence['action']>('notify')
  const [editingId, setEditingId] = useState<string | null>(null)
  const [err, setErr] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [armedDelete, setArmedDelete] = useState<string | null>(null)
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function resetForm() {
    setName('')
    setLat('')
    setLng('')
    setRadius(500)
    setAction('notify')
    setEditingId(null)
  }

  function editFence(g: Geofence) {
    setName(g.name)
    setLat(String(g.latitude))
    setLng(String(g.longitude))
    setRadius(g.radius_m)
    setAction(g.action)
    setEditingId(g.id)
    setErr('')
    setStatus('')
  }

  function useLastLocation() {
    const latest = device?._latest
    if (!latest) return
    setLat(String(latest.latitude))
    setLng(String(latest.longitude))
  }

  async function save() {
    if (!device) return
    const n = name.trim()
    const la = Number(lat)
    const lo = Number(lng)
    const r = Math.round(Number(radius))
    if (!n || n.length > 100) {
      setErr('Name must be 1–100 characters.')
      return
    }
    if (!isFinite(la) || la < -90 || la > 90) {
      setErr('Latitude must be between -90 and 90.')
      return
    }
    if (!isFinite(lo) || lo < -180 || lo > 180) {
      setErr('Longitude must be between -180 and 180.')
      return
    }
    if (!isFinite(r) || r < 50 || r > 5000) {
      setErr('Radius must be between 50 and 5000 metres.')
      return
    }
    setBusy(true)
    setErr('')
    setStatus('Saving…')
    try {
      const body = { name: n, latitude: la, longitude: lo, radius_m: r, action }
      if (editingId) {
        await api('/devices/' + device.id + '/geofences/' + editingId, { method: 'PUT', body })
        setStatus('Geofence updated.')
      } else {
        await api('/devices/' + device.id + '/geofences', { method: 'POST', body })
        setStatus('Geofence created.')
      }
      resetForm()
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
      setStatus('')
    } finally {
      setBusy(false)
    }
  }

  // Two-click inline confirm (same pattern as DevicesPanel sign-out):
  // first click arms the link, second click within 10 s deletes the fence.
  async function remove(id: string) {
    if (!device) return
    if (armedDelete !== id) {
      setArmedDelete(id)
      if (armTimer.current) clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => setArmedDelete(null), 10000)
      return
    }
    if (armTimer.current) clearTimeout(armTimer.current)
    setArmedDelete(null)
    try {
      await api('/devices/' + device.id + '/geofences/' + id, { method: 'DELETE' })
      if (editingId === id) resetForm()
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <section className="card">
      <h2>
        Geofences <span className="muted">{device ? '— ' + device.name : ''}</span>
      </h2>
      <p className="muted">
        Named circles around places that matter — alerts fire when the phone enters or leaves one; Lock and Lost Mode
        fire when it leaves. This list refreshes every 30 seconds (near-live, not real-time).
      </p>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !device ? (
          <p className="muted">Select a device to see its geofences.</p>
        ) : geofences.length === 0 ? (
          <p className="muted">No geofences yet — add one below.</p>
        ) : (
          <table>
            <tbody>
              <tr>
                <th>Name</th>
                <th>Radius</th>
                <th>Action</th>
                <th>Centre</th>
                <th>Created</th>
                <th></th>
              </tr>
              {geofences.map((g) => (
                <tr key={g.id}>
                  <td>{g.name}</td>
                  <td>{g.radius_m} m</td>
                  <td>{ACTION_LABELS[g.action]}</td>
                  <td>
                    {g.latitude.toFixed(4)}, {g.longitude.toFixed(4)}
                  </td>
                  <td>{fmtTime(g.created_at)}</td>
                  <td>
                    <button className="link" onClick={() => editFence(g)}>
                      Edit
                    </button>{' '}
                    <button className="link" onClick={() => remove(g.id)}>
                      {armedDelete === g.id ? 'Click again to delete' : 'Delete'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <h3>{editingId ? 'Edit geofence' : 'Add geofence'}</h3>
      <div className="row">
        <input placeholder="Name (e.g. Home)" value={name} onChange={(e) => setName(e.target.value)} />
        <input
          type="number"
          step="any"
          placeholder="Latitude (e.g. 22.3569)"
          value={lat}
          onChange={(e) => setLat(e.target.value)}
        />
        <input
          type="number"
          step="any"
          placeholder="Longitude (e.g. 91.7832)"
          value={lng}
          onChange={(e) => setLng(e.target.value)}
        />
      </div>
      <div className="row">
        <input
          type="range"
          min={50}
          max={5000}
          step={50}
          value={radius}
          onChange={(e) => setRadius(Number(e.target.value))}
        />
        <input
          type="number"
          min={50}
          max={5000}
          step={1}
          value={radius}
          onChange={(e) => setRadius(Number(e.target.value))}
          aria-label="Radius in metres"
        />
      </div>
      <p className="muted">Radius: {radius} m (50–5000 m)</p>
      <div className="row">
        <select value={action} onChange={(e) => setAction(e.target.value as Geofence['action'])}>
          <option value="notify">Notify only — just an alert</option>
          <option value="lock">Lock — lock the phone when it leaves</option>
          <option value="lost_mode">Lost Mode — turn on Lost Mode when it leaves</option>
        </select>
      </div>
      <p className="muted">{ACTION_HELP[action]}</p>
      <div className="row">
        <button disabled={!device || busy} onClick={save}>
          {editingId ? 'Save changes' : 'Add geofence'}
        </button>
        {editingId && (
          <button
            className="secondary"
            onClick={() => {
              resetForm()
              setErr('')
              setStatus('')
            }}
          >
            Cancel
          </button>
        )}
        <button className="secondary" disabled={!device || !device._latest} onClick={useLastLocation}>
          Use last known location
        </button>
      </div>
      {status && <p className="muted">{status}</p>}
      <p className="err">{err}</p>
      <h3>Recent events</h3>
      <div>
        {!device ? (
          <p className="muted">Select a device to see its geofence events.</p>
        ) : events.length === 0 ? (
          <p className="muted">No geofence events yet.</p>
        ) : (
          <ul>
            {events.map((ev) => (
              <li key={ev.id}>
                {ev.transition === 'enter' ? 'Entered ' : 'Left '} {ev.geofence_name || 'Unknown geofence'} ·{' '}
                {relTime(ev.recorded_at)}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
