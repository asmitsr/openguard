import type { SecurityEvent } from './api'
import { fmtTime } from './util'

const EVENT_LABELS: Record<string, string> = {
  sim_changed: 'SIM changed — check if this was you',
  admin_disabled: 'Device admin disabled',
  location_permission_revoked: 'Location permission revoked',
  location_disabled: 'Location disabled',
  low_battery: 'Low battery',
  sync_gap: 'Sync gap',
}

export default function AlertsPanel({
  deviceName,
  events,
  listErr,
}: {
  deviceName: string
  events: SecurityEvent[]
  listErr: string
}) {
  return (
    <section className="card">
      <h2>
        Alerts <span className="muted">{deviceName ? '— ' + deviceName : ''}</span>
      </h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !deviceName ? (
          <p className="muted">Select a device to see its security alerts.</p>
        ) : events.length === 0 ? (
          <p className="muted">No security alerts for this device.</p>
        ) : (
          <ul>
            {events.map((ev, i) => (
              <li key={i}>
                <b>{EVENT_LABELS[ev.event_type] || ev.event_type}</b> — {fmtTime(ev.created_at)}{' '}
                <span className="muted">({ev.confirmed ? 'Confirmed' : 'Indication'})</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
