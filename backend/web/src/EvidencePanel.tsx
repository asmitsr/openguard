import { useState } from 'react'
import { api, type Device, type EvidenceClip } from './api'
import { fmtTime } from './util'

const CAMERA_LABELS: Record<string, string> = {
  front: 'Front camera',
  back: 'Back camera',
  audio: 'Audio only',
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// Evidence clips: recorded only while Lost Mode is on, ~12 seconds each,
// alternating front and back camera (audio-only when the camera is
// disabled). Clip bytes live in the owner's Google Drive; this panel
// shows metadata + Drive links only. Near-live: the list refreshes
// every 30 seconds — never real-time.
export default function EvidencePanel({
  device,
  clips,
  listErr,
  onChanged,
}: {
  device?: Device | null
  clips: EvidenceClip[]
  listErr: string
  onChanged: () => void // reload the clip list (after a capture is requested)
}) {
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  // Drive connection is on-device state the server never sees; infer it
  // from the newest clip instead.
  const driveStatus = !device
    ? ''
    : clips.length === 0
      ? 'Unknown — no clips yet.'
      : clips[0].storage === 'drive'
        ? 'Connected — clips are uploading to your Google Drive.'
        : 'Not connected — clips stay on the phone. Connect Google Drive in the app’s Settings to get them in your Drive.'

  async function captureNow() {
    if (!device) return
    setBusy(true)
    setStatus('Sending…')
    try {
      await api('/devices/' + device.id + '/commands', { method: 'POST', body: { action: 'evidence_capture' } })
      setStatus('Capture requested — the clip appears below within ~30 seconds when the phone is online and Lost Mode is on.')
      onChanged()
    } catch (e) {
      setStatus((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <h2>
        Evidence <span className="muted">{device ? '— ' + device.name : ''}</span>
      </h2>
      <p className="muted">
        Short clips recorded only while Lost Mode is on — about 12 seconds each, alternating front and back camera,
        audio-only when the camera is disabled. Clips go to your Google Drive; this list refreshes every 30 seconds
        (near-live, not real-time). The phone’s camera and microphone indicators always show while recording.
      </p>
      <p className="muted">Google Drive: {driveStatus}</p>
      <div className="row">
        <button className="secondary" disabled={!device || busy} onClick={captureNow}>
          Capture now
        </button>
      </div>
      {status && <p className="muted">{status}</p>}
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !device ? (
          <p className="muted">Select a device to see its evidence clips.</p>
        ) : clips.length === 0 ? (
          <p className="muted">No clips yet — clips appear here once Lost Mode records them.</p>
        ) : (
          <table>
            <tbody>
              <tr>
                <th>Time</th>
                <th>Camera</th>
                <th>Length</th>
                <th>Size</th>
                <th>Stored in</th>
                <th>Clip</th>
              </tr>
              {clips.map((clip) => (
                <tr key={clip.id}>
                  <td>{fmtTime(clip.recorded_at)}</td>
                  <td>{CAMERA_LABELS[clip.camera] || clip.camera}</td>
                  <td>{clip.duration_s}s</td>
                  <td>{fmtSize(clip.size_bytes)}</td>
                  <td>{clip.storage === 'drive' ? 'Google Drive' : 'On device'}</td>
                  <td>
                    {clip.drive_link ? (
                      <a href={clip.drive_link} target="_blank" rel="noreferrer">
                        Open in Drive
                      </a>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  )
}
