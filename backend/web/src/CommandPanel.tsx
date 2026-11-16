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
  // Remote controls (Workstream B): payload values are strings, matching
  // the server contract and the Android client's Map<String, String>.
  const [volumeStream, setVolumeStream] = useState('all')
  const [volumeLevel, setVolumeLevel] = useState(100)
  const [brightnessLevel, setBrightnessLevel] = useState(100)
  const [timeoutSeconds, setTimeoutSeconds] = useState('60')
  const [speakText, setSpeakText] = useState('')
  const [wallpaperText, setWallpaperText] = useState('')
  const [wallpaperWhich, setWallpaperWhich] = useState('lock')

  // Selecting another device closes the erase panel and command detail,
  // as in the original dashboard.
  useEffect(() => {
    setShowErase(false)
    setDetail(null)
  }, [device?.id])

  const online = isOnline(device)
  const enabled = !!device && !busy

  async function sendCommand(action: string, payload?: Record<string, string>) {
    if (!device) return
    const body: Record<string, unknown> = { action }
    if (payload) body.payload = payload
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
      <h3>Remote controls</h3>
      <p className="muted">
        Brightness, screen timeout and auto-rotate need “Modify system settings” on the phone; Do Not Disturb needs DND access; the torch
        needs camera permission. Each command reports the exact missing grant. The torch is unavailable while Lost Mode has the camera
        disabled.
      </p>
      <div className="row" style={{ alignItems: 'end' }}>
        <label className="muted">
          Volume stream
          <select value={volumeStream} onChange={(e) => setVolumeStream(e.target.value)} style={{ width: '100%', padding: 9, borderRadius: 8, border: '1px solid #c9d2dc' }}>
            <option value="all">All streams</option>
            <option value="media">Media</option>
            <option value="ring">Ring</option>
            <option value="alarm">Alarm</option>
            <option value="notification">Notification</option>
          </select>
        </label>
        <label className="muted">
          Volume — {volumeLevel}%
          <input type="range" min={0} max={100} value={volumeLevel} onChange={(e) => setVolumeLevel(+e.target.value)} />
        </label>
        <button
          className="secondary"
          disabled={!enabled}
          onClick={() => sendCommand('set_volume', { stream: volumeStream, level: String(volumeLevel) })}
        >
          Set volume
        </button>
      </div>
      <div className="row" style={{ alignItems: 'end', marginTop: 8 }}>
        <label className="muted">
          Brightness — {brightnessLevel}%
          <input type="range" min={0} max={100} value={brightnessLevel} onChange={(e) => setBrightnessLevel(+e.target.value)} />
        </label>
        <button
          className="secondary"
          disabled={!enabled}
          onClick={() => sendCommand('set_brightness', { level: String(brightnessLevel) })}
        >
          Set brightness
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_auto_brightness', { enabled: 'true' })}>
          Auto-brightness on
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_auto_brightness', { enabled: 'false' })}>
          Auto-brightness off
        </button>
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('torch_on')}>
          Torch on
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('torch_off')}>
          Torch off
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('wake_screen')}>
          Wake screen
        </button>
        <button
          className="secondary"
          disabled={!enabled}
          title="Needs Lost Mode on — recording only runs while the phone is marked lost"
          onClick={() => sendCommand('evidence_capture')}
        >
          Capture evidence
        </button>
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_ringer_mode', { mode: 'normal' })}>
          Ringer: normal
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_ringer_mode', { mode: 'vibrate' })}>
          Ringer: vibrate
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_ringer_mode', { mode: 'silent' })}>
          Ringer: silent
        </button>
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_dnd', { mode: 'off' })}>
          DND off
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_dnd', { mode: 'alarms' })}>
          DND: alarms only
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_dnd', { mode: 'priority' })}>
          DND: priority only
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_dnd', { mode: 'none' })}>
          DND: total silence
        </button>
      </div>
      <div className="row" style={{ alignItems: 'end', marginTop: 8 }}>
        <label className="muted">
          Screen timeout
          <select value={timeoutSeconds} onChange={(e) => setTimeoutSeconds(e.target.value)} style={{ width: '100%', padding: 9, borderRadius: 8, border: '1px solid #c9d2dc' }}>
            <option value="15">15 seconds</option>
            <option value="30">30 seconds</option>
            <option value="60">1 minute</option>
            <option value="120">2 minutes</option>
            <option value="300">5 minutes</option>
            <option value="600">10 minutes</option>
            <option value="1800">30 minutes</option>
          </select>
        </label>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_screen_timeout', { seconds: timeoutSeconds })}>
          Set timeout
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_auto_rotate', { enabled: 'true' })}>
          Auto-rotate on
        </button>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('set_auto_rotate', { enabled: 'false' })}>
          Auto-rotate off
        </button>
      </div>
      <div style={{ marginTop: 8 }}>
        <input
          placeholder="Spoken message (read aloud at full volume; empty = “This phone is lost. Please contact the owner.”)"
          maxLength={200}
          value={speakText}
          onChange={(e) => setSpeakText(e.target.value)}
        />
        <div className="row">
          <button
            className="secondary"
            disabled={!enabled}
            onClick={() => sendCommand('speak_message', speakText.trim() ? { message: speakText.trim() } : undefined)}
          >
            Speak on phone
          </button>
        </div>
      </div>
      <div style={{ marginTop: 8 }}>
        <input
          placeholder="Wallpaper message (empty = “This phone is lost — please contact the owner”)"
          maxLength={200}
          value={wallpaperText}
          onChange={(e) => setWallpaperText(e.target.value)}
        />
        <div className="row" style={{ alignItems: 'end' }}>
          <label className="muted">
            Wallpaper target
            <select value={wallpaperWhich} onChange={(e) => setWallpaperWhich(e.target.value)} style={{ width: '100%', padding: 9, borderRadius: 8, border: '1px solid #c9d2dc' }}>
              <option value="lock">Lock screen</option>
              <option value="home">Home screen</option>
              <option value="both">Both</option>
            </select>
          </label>
          <button
            className="secondary"
            disabled={!enabled}
            onClick={() =>
              sendCommand('set_wallpaper', {
                which: wallpaperWhich,
                ...(wallpaperText.trim() ? { message: wallpaperText.trim() } : {}),
              })
            }
          >
            Set wallpaper
          </button>
        </div>
        <p className="muted">Setting the wallpaper replaces the previous one; it cannot be restored afterwards.</p>
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button className="secondary" disabled={!enabled} onClick={() => sendCommand('bluetooth_on')}>
          Turn on Bluetooth
        </button>
      </div>
      <p className="muted">
        Best-effort: Android 13+ does not let apps turn Bluetooth on silently. The phone shows a prompt the person holding it must accept.
      </p>
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
