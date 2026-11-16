import { useRef, useState } from 'react'
import { api, deriveKey, getEmail, stepUp, type AuditEvent, type PrivacyInfo, type Session } from './api'
import { fmtTime, relTime } from './util'

export function SessionsPanel({
  sessions,
  listErr,
  onChanged,
}: {
  sessions: Session[] | null
  listErr: string
  onChanged: () => void
}) {
  const [err, setErr] = useState('')
  const [armed, setArmed] = useState(false)
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  async function revokeOthers() {
    if (!armed) {
      setArmed(true)
      if (armTimer.current) clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => setArmed(false), 10000)
      return
    }
    setArmed(false)
    if (armTimer.current) clearTimeout(armTimer.current)
    try {
      const r = await api<{ revoked: number }>('/auth/sessions/revoke-others', { method: 'POST' })
      setErr('Signed out ' + r.revoked + ' other session(s).')
      onChanged()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <section className="card">
      <h2>Sessions</h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : sessions === null ? (
          <p className="muted">Loading…</p>
        ) : sessions.length === 0 ? (
          <p className="muted">No sessions.</p>
        ) : (
          sessions.map((s) => (
            <div key={s.id} style={{ marginBottom: 10 }}>
              <b>{s.user_agent || 'Unknown device'}</b>
              <br />
              <span className="muted">
                {s.ip || ''} · created {fmtTime(s.created_at)} · last used {relTime(s.last_used_at)}
                {s.current ? (
                  <>
                    {' · '}
                    <b>current</b>
                  </>
                ) : (
                  ''
                )}
              </span>
              {!s.current && (
                <>
                  {' '}
                  <button
                    className="link"
                    onClick={async () => {
                      try {
                        await api('/auth/sessions/' + s.id, { method: 'DELETE' })
                        onChanged()
                      } catch (e) {
                        alert((e as Error).message)
                      }
                    }}
                  >
                    Revoke
                  </button>
                </>
              )}
            </div>
          ))
        )}
      </div>
      <button className="secondary" onClick={revokeOthers}>
        {armed ? 'Click again to sign out all other sessions' : 'Sign out all other sessions'}
      </button>
      <p className="err">{err}</p>
    </section>
  )
}

export function RecoveryPanel({
  count,
  onCount,
}: {
  count: number | null | undefined
  onCount: (n: number) => void
}) {
  const [showGenerate, setShowGenerate] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [codes, setCodes] = useState<string[] | null>(null)
  const [saved, setSaved] = useState(false)
  const [copyText, setCopyText] = useState('Copy codes')
  const [err, setErr] = useState('')

  async function generate() {
    if (!password || !getEmail()) {
      setErr('Enter your password to confirm.')
      return
    }
    setBusy(true)
    try {
      const token = await stepUp(password)
      const r = await api<{ codes: string[] }>('/auth/recovery-codes/generate', {
        method: 'POST',
        body: { step_up_token: token },
      })
      setShowGenerate(false)
      setPassword('')
      setCodes(r.codes)
      setSaved(false)
      setCopyText('Copy codes')
      onCount(r.codes.length)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card">
      <h2>Recovery codes</h2>
      <p className="muted">
        <span>{count === undefined ? 'Loading…' : count === null ? '—' : count}</span> unused codes remaining.
      </p>
      <p className="muted">Recovery codes let you reset your password if you forget it. Generating new codes invalidates the old ones.</p>
      {!showGenerate && (
        <button
          className="secondary"
          onClick={() => {
            setShowGenerate(true)
            setErr('')
          }}
        >
          Generate new codes
        </button>
      )}
      {showGenerate && (
        <div style={{ marginTop: 10 }}>
          <input type="password" placeholder="Your password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <button onClick={generate} disabled={busy}>
            {busy ? 'Generating…' : 'Confirm & generate'}
          </button>
        </div>
      )}
      {codes && (
        <div style={{ background: '#fff8e1', border: '1px solid #c6a46a', borderRadius: 8, padding: 12, marginTop: 10 }}>
          <b>Save these codes — they are shown once only.</b>
          <p style={{ fontFamily: 'monospace', wordBreak: 'break-all', whiteSpace: 'pre-line' }}>{codes.join('\n')}</p>
          <button
            className="secondary"
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(codes.join('\n'))
                setCopyText('Copied!')
              } catch (_) {
                setCopyText('Select the codes and copy manually')
              }
            }}
          >
            {copyText}
          </button>
          <p>
            <label>
              <input type="checkbox" style={{ width: 'auto' }} checked={saved} onChange={(e) => setSaved(e.target.checked)} /> I have
              saved these codes
            </label>
          </p>
          <button disabled={!saved} onClick={() => setCodes(null)}>
            Close
          </button>
        </div>
      )}
      <p className="err">{err}</p>
    </section>
  )
}

export function AuditPanel({ events, listErr }: { events: AuditEvent[] | null; listErr: string }) {
  return (
    <section className="card">
      <h2>Audit log</h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : events === null ? (
          <p className="muted">Loading…</p>
        ) : events.length === 0 ? (
          <p className="muted">No audit events yet.</p>
        ) : (
          <ul>
            {events.map((a, i) => (
              <li key={i}>
                <b>{a.event}</b> — {fmtTime(a.created_at)}
                {a.detail ? (
                  <>
                    <br />
                    <span className="muted">{a.detail}</span>
                  </>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

export function PrivacyPanel({
  privacy,
  listErr,
  onLoggedOut,
}: {
  privacy: PrivacyInfo | null
  listErr: string
  onLoggedOut: () => void
}) {
  const [password, setPassword] = useState('')
  const [err, setErr] = useState('')
  const [armed, setArmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Two-click inline confirmation: native confirm() is auto-dismissed by
  // automated browsers (and is easy to miss), which made the button silently
  // do nothing. First click arms, second click within 10 s deletes.
  async function deleteAccount() {
    if (!password) {
      setErr('Enter your password to confirm.')
      return
    }
    if (!armed) {
      setArmed(true)
      setErr('This cannot be undone. Click the button again to confirm.')
      if (armTimer.current) clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => {
        setArmed(false)
        setErr('')
      }, 10000)
      return
    }
    setArmed(false)
    if (armTimer.current) clearTimeout(armTimer.current)
    const email = getEmail()
    if (!email) {
      setErr('Could not find your email — please log in again.')
      return
    }
    setBusy(true)
    try {
      const s = await api<{ salt: string }>('/auth/salt?email=' + encodeURIComponent(email))
      await api('/auth/account', { method: 'DELETE', body: { derived_key: await deriveKey(password, s.salt) } })
      onLoggedOut()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const list = (arr?: string[]) =>
    arr && arr.length ? (
      <ul>
        {arr.map((x, i) => (
          <li key={i}>{x}</li>
        ))}
      </ul>
    ) : (
      <p className="muted">None.</p>
    )

  return (
    <section className="card">
      <h2>Privacy</h2>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : !privacy ? (
          <p className="muted">Loading…</p>
        ) : (
          <>
            <p>
              Retention: <b>{privacy.retention_days} days</b> · Locations stored: <b>{privacy.location_count}</b> · Devices:{' '}
              <b>{privacy.device_count}</b>
            </p>
            <h3>Data stored on the server</h3>
            {list(privacy.server_data)}
            <h3>Third parties</h3>
            {list(privacy.third_parties)}
          </>
        )}
      </div>
      <h3>Delete account</h3>
      <p className="muted">Permanently deletes your account, devices and locations.</p>
      <input type="password" placeholder="Confirm with your password" value={password} onChange={(e) => setPassword(e.target.value)} />
      <button className="danger" onClick={deleteAccount} disabled={busy}>
        {busy ? 'Verifying…' : armed ? 'Click again to confirm deletion' : 'Delete my account'}
      </button>
      <p className="err">{err}</p>
    </section>
  )
}
