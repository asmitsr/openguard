import { useState } from 'react'
import {
  browserSupportsWebAuthn,
  startAuthentication,
  type PublicKeyCredentialRequestOptionsJSON,
} from '@simplewebauthn/browser'
import { api, deriveKey, isPasskeyCancelled, randomSalt, setTokens } from './api'

export default function AuthView({ onAuthed }: { onAuthed: () => void }) {
  const [isRegister, setIsRegister] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [pkBusy, setPkBusy] = useState(false)
  const [showRecover, setShowRecover] = useState(false)
  const [recEmail, setRecEmail] = useState('')
  const [recCode, setRecCode] = useState('')
  const [recPassword, setRecPassword] = useState('')
  const [recErr, setRecErr] = useState('')
  const [recBusy, setRecBusy] = useState(false)

  async function submit() {
    const em = email.trim()
    if (!em || !password) {
      setErr('Enter email and password.')
      return
    }
    if (isRegister && password.length < 8) {
      setErr('Password must be at least 8 characters.')
      return
    }
    setBusy(true)
    try {
      let body: Record<string, string>
      if (isRegister) {
        const salt = randomSalt()
        body = { email: em, salt, derived_key: await deriveKey(password, salt) }
      } else {
        const s = await api<{ salt: string }>('/auth/salt?email=' + encodeURIComponent(em))
        body = { email: em, derived_key: await deriveKey(password, s.salt) }
      }
      const d = await api<{ access_token: string; refresh_token: string }>(
        isRegister ? '/auth/register' : '/auth/login',
        { method: 'POST', body },
      )
      setTokens(d.access_token, d.refresh_token, em)
      setErr('')
      onAuthed()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function passkeyLogin() {
    const em = email.trim()
    if (!em) {
      setErr('Enter your email first.')
      return
    }
    if (!browserSupportsWebAuthn()) {
      setErr('This browser does not support passkeys.')
      return
    }
    setPkBusy(true)
    try {
      let lastErr: unknown = null
      for (let attempt = 0; attempt < 2; attempt++) {
        let inVerify = false
        try {
          // Challenge + ceremony + verify, retried once with a fresh
          // challenge if the verify fails (the challenge may have been
          // issued by another isolate). A cancelled prompt is not retried.
          const options = await api<PublicKeyCredentialRequestOptionsJSON>('/passkeys/login/challenge', {
            method: 'POST',
            body: { email: em },
          })
          const credential = await startAuthentication({ optionsJSON: options })
          inVerify = true
          const d = await api<{ access_token: string; refresh_token: string }>('/passkeys/login/verify', {
            method: 'POST',
            body: { email: em, credential },
          })
          setTokens(d.access_token, d.refresh_token, em)
          setErr('')
          onAuthed()
          return
        } catch (e) {
          if (isPasskeyCancelled(e)) {
            setErr('Passkey sign-in cancelled.')
            return
          }
          // No passkey for this email — a fresh challenge cannot fix it,
          // so show the server's message as-is instead of re-prompting.
          if ((e as Error).message === 'invalid email or passkey') {
            setErr((e as Error).message)
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
      setPkBusy(false)
    }
  }

  async function recover() {
    const em = recEmail.trim()
    const code = recCode.trim()
    if (!em || !code) {
      setRecErr('Enter your email and recovery code.')
      return
    }
    if (recPassword.length < 8) {
      setRecErr('New password must be at least 8 characters.')
      return
    }
    setRecBusy(true)
    try {
      const salt = randomSalt()
      await api('/auth/recover', {
        method: 'POST',
        body: { email: em, code, salt, derived_key: await deriveKey(recPassword, salt) },
      })
      setShowRecover(false)
      setRecCode('')
      setRecPassword('')
      setIsRegister(false)
      setErr('Password changed — log in with your new password.')
    } catch (e) {
      setRecErr((e as Error).message)
    } finally {
      setRecBusy(false)
    }
  }

  return (
    <section className="card">
      <h2>{isRegister ? 'Create account' : 'Log in'}</h2>
      <p className="err">{err}</p>
      <input type="email" placeholder="Email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      <input
        type="password"
        placeholder="Password"
        autoComplete="current-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <div className="row">
        <button onClick={submit} disabled={busy}>
          {busy ? 'Verifying…' : isRegister ? 'Register' : 'Log in'}
        </button>
      </div>
      {!isRegister && (
        <div className="row">
          <button className="secondary" onClick={passkeyLogin} disabled={busy || pkBusy}>
            {pkBusy ? 'Waiting for passkey…' : 'Sign in with passkey'}
          </button>
        </div>
      )}
      <p className="muted">
        <button
          className="link"
          onClick={() => {
            setIsRegister(!isRegister)
            setErr('')
          }}
        >
          {isRegister ? 'Log in instead' : 'Create an account instead'}
        </button>
      </p>
      <p className="muted">
        <button
          className="link"
          onClick={() => {
            setShowRecover(!showRecover)
            if (!showRecover && !recEmail) setRecEmail(email)
            setRecErr('')
          }}
        >
          Forgot password? Recover with a recovery code
        </button>
      </p>
      {showRecover && (
        <div>
          <input type="email" placeholder="Email" autoComplete="email" value={recEmail} onChange={(e) => setRecEmail(e.target.value)} />
          <input placeholder="Recovery code (XXXX-XXXX-XXXX)" value={recCode} onChange={(e) => setRecCode(e.target.value)} />
          <input
            type="password"
            placeholder="New password (8+ characters)"
            autoComplete="new-password"
            value={recPassword}
            onChange={(e) => setRecPassword(e.target.value)}
          />
          <button onClick={recover} disabled={recBusy}>
            {recBusy ? 'Recovering…' : 'Recover password'}
          </button>
          <p className="err">{recErr}</p>
        </div>
      )}
    </section>
  )
}
