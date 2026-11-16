import type { Location } from './api'
import { fmtTime } from './util'

export default function HistoryPanel({
  deviceName,
  locs,
  hasMore,
  listErr,
  exportDisabled,
  historyLoaded,
  onLoadMore,
  onExport,
}: {
  deviceName: string
  locs: Location[]
  hasMore: boolean
  listErr: string
  exportDisabled: boolean
  historyLoaded: boolean
  onLoadMore: () => void
  onExport: (format: 'csv' | 'json') => void
}) {
  return (
    <section className="card">
      <h2>
        History <span className="muted">{deviceName ? '— ' + deviceName : ''}</span>
      </h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !deviceName || !historyLoaded ? (
          <p className="muted">Select a device to see its location history.</p>
        ) : locs.length === 0 ? (
          <p className="muted">No locations recorded yet for this device.</p>
        ) : (
          <table>
            <tbody>
              <tr>
                <th>Time</th>
                <th>Accuracy</th>
                <th>Provider</th>
              </tr>
              {locs.map((l, i) => (
                <tr key={l.id || i}>
                  <td>{fmtTime(l.recorded_at)}</td>
                  <td>±{l.accuracy} m</td>
                  <td>{l.provider || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <button className="secondary" disabled={!hasMore} onClick={onLoadMore}>
          Load more
        </button>
        <button className="secondary" disabled={exportDisabled} onClick={() => onExport('csv')}>
          Export CSV
        </button>
        <button className="secondary" disabled={exportDisabled} onClick={() => onExport('json')}>
          Export JSON
        </button>
      </div>
    </section>
  )
}
