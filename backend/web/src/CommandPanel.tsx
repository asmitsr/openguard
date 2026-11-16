import { useEffect, useState } from 'react'
import { api, stepUp, type Command, type CommandDetail, type Device } from './api'
import { fmtTime } from './util'

// Online = seen within the last 5 minutes (same rule the server uses for
// erase). Offline devices still receive commands: they queue for 24 hours.
function isOnline(d?: Device | null): boolean {
  return !!(d && d.last_seen_at && Date.now() - new Date(d.last_seen_at).getTime() < 5 * 60 * 1000)
}

export default function CommandPanel({
  device,
  commands,
  listErr,
  onChanged,
}: {
  device?: Device | null
  commands: Command[]
  listErr: string
  onChanged: () => void // reload commands + devices (after a command is sent)
}) {
  const [lostMessage, setLostMessage] = useState('')
  const [status, setStatus] = useState('Select a device to send commands.')
  const [busy, setBusy] = useState(false)
  const [showErase, setShowErase] = useState(false)
  const [eraseConfirm, setEraseConfirm] = useState('')
  const [erasePassword, setErasePassword] = useState('')
  const [detail, setDetail] = useState<CommandDetail | null>(null)

  // Selecting another device closes the erase panel and command detail,
  // as in the original dashboard.
  useEffect(() => {
    setShowErase(false)
    setDetail(null)
  }, [device?.id])

  const online = isOnline(device)
  const enabled = !!device && !busy

  async function sendCommand(action: string) {
    if (!device) return
    const body: Record<string, unknown> = { action }
    if (action === 'lost_mode_on') {
      const msg = lostMessage.trim()
      if (msg) body.payload = { message: msg }
    }
    setBusy(true)
    setStatus('Sending…')
    try {
      await api('/devices/' + device.id + '/commands', { method: 'POST', body })
      setStatus('Command sent — the phone picks it up within ~15 minutes; queued for up to 24 hours if offline.')
      onChanged()
    } catch (e) {
      setStatus((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  // Erase: inline flow, no confirm(). Type ERASE + password, then step-up
  // auth unlocks the erase command. The button stays disabled while the
  // device is offline — the server refuses offline erase anyway.
  async function submitErase() {
    if (!device) return
    if (eraseConfirm.trim() !== 'ERASE') {
      setStatus('Type ERASE to confirm.')
      return
    }
    if (!erasePassword) {
      setStatus('Enter your password to confirm.')
      return
    }
    setStatus('Verifying…')
    try {
      const token = await stepUp(erasePassword)
      await api('/devices/' + device.id + '/commands', {
        method: 'POST',
        body: { action: 'erase', step_up_token: token },
      })
      setShowErase(false)
      setStatus('Erase command sent — the phone will wipe itself when it picks it up.')
      onChanged()
    } catch (e) {
      setStatus((e as Error).message)
    }
  }

  async function loadDetail(id: string) {
    try {
      setDetail(await api<CommandDetail>('/commands/' + id))
    } catch (e) {
      setStatus((e as Error).message)
    }
  }

  return (
    <section className="card">
      <h2>
        Command Center <span className="muted">{device ? '— ' + device.name : ''}</span>
      </h2>
      <p className="muted">{device ? (online ? 'Online' : 'Offline — commands queue for up to 24 hours') : ''}</p>
      <input placeholder="Lost message (shown on the phone)" maxLength={200} value={lostMessage} onChange={(e) => setLostMessage(e.target.value)} />
      <div className="row">
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('ring')}>
          Ring
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('lock')}>
          Lock
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('lost_mode_on')}>
          Lost Mode ON
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('lost_mode_off')}>
          Lost Mode OFF
        </button>
        <button
          className="danger"
          disabled={!device || !online || busy}
          title={!device || !online ? (device ? 'Erase requires the device to be online' : '') : ''}
          onClick={() => {
            setEraseConfirm('')
            setErasePassword('')
            setShowErase(true)
          }}
        >
          Erase device
        </button>
      </div>
      {showErase && (
        <div style={{ background: '#fdf0ef', border: '1px solid #b3261e', borderRadius: 8, padding: 12, marginTop: 10 }}>
          <b>Erase this device?</b>
          <p className="muted">
            This permanently wipes all apps, photos, files, accounts and SD card contents. The phone stays locked to your Google account by
            Factory Reset Protection — only you can set it up again. The device must be online; erase cannot be queued.
          </p>
          <input placeholder="Type ERASE to confirm" value={eraseConfirm} onChange={(e) => setEraseConfirm(e.target.value)} />
          <input
            type="password"
            placeholder="Your password"
            value={erasePassword}
            onChange={(e) => setErasePassword(e.target.value)}
          />
          <div className="row">
            <button className="danger" onClick={submitErase}>
              Erase device
            </button>
            <button className="secondary" onClick={() => setShowErase(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      <p className="muted">{status}</p>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !device ? null : commands.length === 0 ? (
          <p className="muted">No commands sent yet.</p>
        ) : (
          <table>
            <tbody>
              <tr>
                <th>Action</th>
                <th>Status</th>
                <th>Time</th>
                <th>Result</th>
              </tr>
              {commands.map((cmd) => (
                <tr key={cmd.id} style={{ cursor: 'pointer' }} onClick={() => loadDetail(cmd.id)}>
                  <td>{cmd.action}</td>
                  <td>{cmd.status}</td>
                  <td>{fmtTime(cmd.issued_at)}</td>
                  <td>{cmd.result || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {detail && (
        <div>
          <h3>Command: {detail.command.action}</h3>
          <p className="muted">
            Issued {fmtTime(detail.command.issued_at)} · Expires {fmtTime(detail.command.expires_at)} · Nonce{' '}
            {detail.command.nonce_truncated} · Status {detail.command.status} · Result {detail.command.result || '—'}
          </p>
          {detail.audit.length ? (
            <ul>
              {detail.audit.map((a, i) => (
                <li key={i}>
                  <b>{a.event}</b> — {fmtTime(a.created_at)}
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted">No audit events yet.</p>
          )}
        </div>
      )}
    </section>
  )
}
