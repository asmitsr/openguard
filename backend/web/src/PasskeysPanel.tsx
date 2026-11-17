import { useEffect, useRef, useState } from 'react'
import {
  browserSupportsWebAuthn,
  startRegistration,
  type PublicKeyCredentialCreationOptionsJSON,
} from '@simplewebauthn/browser'
import { api, isPasskeyCancelled, type Passkey } from './api'
import { fmtTime, relTime } from './util'

export default function PasskeysPanel() {
  const [passkeys, setPasskeys] = useState<Passkey[] | null>(null)
  const [listErr, setListErr] = useState('')
  const [name, setName] = useState('This browser')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const armTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  async function load() {
    try {
      setPasskeys(await api<Passkey[]>('/passkeys'))
      setListErr('')
    } catch (e) {
      setListErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function create() {
    if (!browserSupportsWebAuthn()) {
      setErr('This browser does not support passkeys.')
      return
    }
    setBusy(true)
    try {
      let lastErr: unknown = null
      for (let attempt = 0; attempt < 2; attempt++) {
        let inVerify = false
        try {
          // Challenge + ceremony + verify, retried once with a fresh
          // challenge if the verify fails (the challenge may have been
          // issued by another isolate). A cancelled prompt is not retried.
          const options = await api<PublicKeyCredentialCreationOptionsJSON>('/passkeys/register/challenge', {
            method: 'POST',
          })
          const credential = await startRegistration({ optionsJSON: options })
          inVerify = true
          await api('/passkeys/register/verify', {
            method: 'POST',
            body: { name: name.trim() || 'This browser', credential },
          })
          setErr('')
          setName('This browser')
          load()
          return
        } catch (e) {
          if (isPasskeyCancelled(e)) {
            setErr('Passkey creation cancelled.')
            return
          }
          if (!inVerify) throw e
          lastErr = e
        }
      }
      throw lastErr
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  // Two-click inline confirmation, as in SessionsPanel/PrivacyPanel:
  // first click arms, second click within 10 s revokes.
  async function revoke(id: string) {
    if (confirmId !== id) {
      setConfirmId(id)
      if (armTimer.current) clearTimeout(armTimer.current)
      armTimer.current = setTimeout(() => setConfirmId(null), 10000)
      return
    }
    setConfirmId(null)
    if (armTimer.current) clearTimeout(armTimer.current)
    try {
      await api('/passkeys/' + id, { method: 'DELETE' })
      setErr('')
      load()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  return (
    <section className="card">
      <h2>Passkeys</h2>
      <p className="muted">
        A passkey lives on the device that created it. The private key never leaves that device. If you lose the device, sign in with
        your password. Revoking removes the passkey from your account.
      </p>
      <div>
        {listErr ? (
          <p className="err">{listErr}</p>
        ) : passkeys === null ? (
          <p className="muted">Loading…</p>
        ) : passkeys.length === 0 ? (
          <p className="muted">No passkeys yet.</p>
        ) : (
          passkeys.map((p) => (
            <div key={p.id} style={{ marginBottom: 10 }}>
              <b>{p.name}</b>
              <br />
              <span className="muted">
                created {fmtTime(p.created_at)} · last used {relTime(p.last_used_at)}
              </span>{' '}
              <button className="link" onClick={() => revoke(p.id)}>
                {confirmId === p.id ? 'Confirm revoke?' : 'Revoke'}
              </button>
            </div>
          ))
        )}
      </div>
      <input placeholder="Passkey name" value={name} onChange={(e) => setName(e.target.value)} />
      <button onClick={create} disabled={busy}>
        {busy ? 'Creating…' : 'Create passkey'}
      </button>
      <p className="err">{err}</p>
    </section>
  )
}
