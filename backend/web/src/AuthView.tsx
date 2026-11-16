import { useState } from 'react'
import { api, deriveKey, randomSalt, setTokens } from './api'

export default function AuthView({ onAuthed }: { onAuthed: () => void }) {
  const [isRegister, setIsRegister] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
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
