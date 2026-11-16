import { useEffect, useRef, useState } from 'react'
import {
  api,
  clearTokens,
  exportData,
  hasToken,
  setOnSessionExpired,
  type AuditEvent,
  type Command,
  type Device,
  type EvidenceClip,
  type Location,
  type PrivacyInfo,
  type SecurityEvent,
  type Session,
} from './api'
import AuthView from './AuthView'
import DevicesPanel from './DevicesPanel'
import EvidencePanel from './EvidencePanel'
import MapPanel from './MapPanel'
import HistoryPanel from './HistoryPanel'
import CommandPanel from './CommandPanel'
import AlertsPanel from './AlertsPanel'
import { AuditPanel, PrivacyPanel, RecoveryPanel, SessionsPanel } from './AccountPanels'

export default function App() {
  const [authed, setAuthed] = useState(hasToken())
  const [devices, setDevices] = useState<Device[] | null>(null)
  const [devicesErr, setDevicesErr] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [historyLocs, setHistoryLocs] = useState<Location[]>([])
  const [nextBefore, setNextBefore] = useState<string | null>(null)
  const [historyErr, setHistoryErr] = useState('')
  const [historyLoaded, setHistoryLoaded] = useState(false)
  const [commands, setCommands] = useState<Command[]>([])
  const [commandsErr, setCommandsErr] = useState('')
  const [alerts, setAlerts] = useState<SecurityEvent[]>([])
  const [alertsErr, setAlertsErr] = useState('')
  const [evidence, setEvidence] = useState<EvidenceClip[]>([])
  const [evidenceErr, setEvidenceErr] = useState('')
  const [sessions, setSessions] = useState<Session[] | null>(null)
  const [sessionsErr, setSessionsErr] = useState('')
  const [recoveryCount, setRecoveryCount] = useState<number | null | undefined>(undefined)
  const [audit, setAudit] = useState<AuditEvent[] | null>(null)
  const [auditErr, setAuditErr] = useState('')
  const [privacy, setPrivacy] = useState<PrivacyInfo | null>(null)
  const [privacyErr, setPrivacyErr] = useState('')

  // Refs so loaders never close over stale state.
  const selectedRef = useRef<string | null>(null)
  const nextBeforeRef = useRef<string | null>(null)
  selectedRef.current = selected
  nextBeforeRef.current = nextBefore

  async function loadDevices() {
    try {
      const list = await api<Device[]>('/devices')
      for (let i = 0; i < list.length; i++) {
        try {
          list[i]._latest = await api<Location | null>('/devices/' + list[i].id + '/latest')
        } catch (_) {
          list[i]._latest = null
        }
      }
      setDevices(list)
      setDevicesErr('')
      if (selectedRef.current && !list.some((d) => d.id === selectedRef.current)) setSelected(null)
    } catch (e) {
      setDevicesErr((e as Error).message)
    }
  }

  async function loadHistory(more: boolean) {
    const id = selectedRef.current
    if (!id) return
    try {
      const q = '?limit=100' + (more && nextBeforeRef.current ? '&before=' + encodeURIComponent(nextBeforeRef.current) : '')
      const data = await api<{ locations: Location[]; next_before: string | null }>('/devices/' + id + '/locations' + q)
      setHistoryLocs((prev) => (more ? prev.concat(data.locations || []) : data.locations || []))
      setNextBefore(data.next_before || null)
      setHistoryLoaded(true)
      setHistoryErr('')
    } catch (e) {
      setHistoryErr((e as Error).message)
    }
  }

  async function loadCommands() {
    const id = selectedRef.current
    if (!id) return
    try {
      const data = await api<{ commands: Command[] }>('/devices/' + id + '/commands?limit=20')
      setCommands(data.commands || [])
      setCommandsErr('')
    } catch (e) {
      setCommandsErr((e as Error).message)
    }
  }

  async function loadAlerts() {
    const id = selectedRef.current
    if (!id) return
    try {
      const data = await api<{ events: SecurityEvent[] }>('/devices/' + id + '/security-events?limit=50')
      setAlerts(data.events || [])
      setAlertsErr('')
    } catch (e) {
      setAlertsErr((e as Error).message)
    }
  }

  async function loadEvidence() {
    const id = selectedRef.current
    if (!id) return
    try {
      const data = await api<{ clips: EvidenceClip[] }>('/devices/' + id + '/evidence?limit=50')
      setEvidence(data.clips || [])
      setEvidenceErr('')
    } catch (e) {
      setEvidenceErr((e as Error).message)
    }
  }

  async function loadSessions() {
    try {
      setSessions(await api<Session[]>('/auth/sessions'))
      setSessionsErr('')
    } catch (e) {
      setSessionsErr((e as Error).message)
    }
  }

  async function loadRecoveryCodes() {
    try {
      const r = await api<{ remaining: number }>('/auth/recovery-codes')
      setRecoveryCount(r.remaining)
    } catch (_) {
      setRecoveryCount(null)
    }
  }

  async function loadAudit() {
    try {
      setAudit(await api<AuditEvent[]>('/audit'))
      setAuditErr('')
    } catch (e) {
      setAuditErr((e as Error).message)
    }
  }

  async function loadPrivacy() {
    try {
      setPrivacy(await api<PrivacyInfo>('/privacy'))
      setPrivacyErr('')
    } catch (e) {
      setPrivacyErr((e as Error).message)
    }
  }

  function selectDevice(id: string) {
    selectedRef.current = id
    setSelected(id)
    setHistoryErr('')
    // Lists are NOT cleared here: the original dashboard keeps the previous
    // device's history/commands until the new load renders.
    loadHistory(false)
    loadCommands()
    loadAlerts()
    loadEvidence()
  }

  async function logout() {
    try {
      await api('/auth/logout', { method: 'POST' })
    } catch (_) {}
    clearTokens()
    setAuthed(false)
  }

  useEffect(() => {
    setOnSessionExpired(() => setAuthed(false))
  }, [])

  useEffect(() => {
    if (!authed) return
    loadDevices()
    loadSessions()
    loadRecoveryCodes()
    loadAudit()
    loadPrivacy()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authed])

  // Near-live evidence: poll the clip list every 30 seconds while a
  // device is selected, so a requested capture shows up shortly after
  // the phone records and uploads it. Never real-time.
  useEffect(() => {
    if (!authed || !selected) return
    const t = setInterval(() => loadEvidence(), 30000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authed, selected])

  const selDevice = (devices || []).find((d) => d.id === selected) || null

  return (
    <>
      <header>
        <h1>
          OpenGuard <span>• free device tracking</span>
        </h1>
        {authed && (
          <button className="secondary" onClick={logout}>
            Log out
          </button>
        )}
      </header>
      <main>
        {!authed ? (
          <AuthView onAuthed={() => setAuthed(true)} />
        ) : (
          <>
            <DevicesPanel
              devices={devices}
              selected={selected}
              listErr={devicesErr}
              onSelect={selectDevice}
              onChanged={() => {
                loadDevices()
                loadPrivacy()
              }}
            />
            <MapPanel devices={devices || []} selected={selected} historyLocs={historyLocs} onSelect={selectDevice} />
            <HistoryPanel
              deviceName={selDevice ? selDevice.name : ''}
              locs={historyLocs}
              hasMore={!!nextBefore}
              listErr={historyErr}
              exportDisabled={!historyLoaded}
              historyLoaded={historyLoaded}
              onLoadMore={() => loadHistory(true)}
              onExport={async (format) => {
                if (!selected) return
                try {
                  await exportData(selected, format)
                } catch (e) {
                  alert((e as Error).message)
                }
              }}
            />
            <div className="grid">
              <CommandPanel
                device={selDevice}
                commands={commands}
                listErr={commandsErr}
                onChanged={() => {
                  loadCommands()
                  loadDevices()
                }}
              />
              <AlertsPanel deviceName={selDevice ? selDevice.name : ''} events={alerts} listErr={alertsErr} />
            </div>
            <EvidencePanel
              device={selDevice}
              clips={evidence}
              listErr={evidenceErr}
              onChanged={() => {
                loadEvidence()
                loadCommands()
              }}
            />
            <div className="grid">
              <SessionsPanel sessions={sessions} listErr={sessionsErr} onChanged={loadSessions} />
              <RecoveryPanel count={recoveryCount} onCount={setRecoveryCount} />
              <AuditPanel events={audit} listErr={auditErr} />
            </div>
            <PrivacyPanel privacy={privacy} listErr={privacyErr} onLoggedOut={() => setAuthed(false)} />
          </>
        )}
      </main>
    </>
  )
}
