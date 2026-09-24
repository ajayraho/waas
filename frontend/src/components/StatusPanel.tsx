import { useEffect, useState } from 'react'
import { fetchHealth, type Health, type ServiceName } from '../lib/health'
import type { SocketState } from '../lib/stomp'
import { Hint } from './Hint'

type Probe = { health?: Health; error?: string }

const POLL_MS = 5000

function useHealth(service: ServiceName): Probe {
  const [probe, setProbe] = useState<Probe>({})
  useEffect(() => {
    let alive = true
    const ctrl = new AbortController()
    const tick = async () => {
      try {
        const health = await fetchHealth(service, ctrl.signal)
        if (alive) setProbe({ health })
      } catch (e) {
        if (alive) setProbe({ error: e instanceof Error ? e.message : 'unreachable' })
      }
    }
    void tick()
    const id = setInterval(tick, POLL_MS)
    return () => {
      alive = false
      ctrl.abort()
      clearInterval(id)
    }
  }, [service])
  return probe
}

type Tone = 'ok' | 'bad' | 'pending'

function Row({ label, tone, value }: { label: string; tone: Tone; value: string }) {
  return (
    <li className="status-row">
      <span className={`dot dot-${tone}`} aria-hidden />
      <span className="status-label">{label}</span>
      <span className="status-value">{value}</span>
    </li>
  )
}

function toneOf(status?: string): Tone {
  if (!status) return 'pending'
  return status === 'UP' ? 'ok' : 'bad'
}

function ServiceBlock({ title, probe, deps }: { title: string; probe: Probe; deps: string[] }) {
  const h = probe.health
  return (
    <div className="status-block">
      <h3>{title}</h3>
      <ul>
        <Row
          label="service"
          tone={probe.error ? 'bad' : toneOf(h?.status)}
          value={probe.error ? 'unreachable' : (h?.status ?? '…')}
        />
        {deps.map((d) => {
          const s = h?.components?.[d]?.status
          return <Row key={d} label={d} tone={probe.error ? 'bad' : toneOf(s)} value={s ?? '—'} />
        })}
      </ul>
    </div>
  )
}

interface GatewayStats {
  sessions: number
  subscriptions: number
  eventsReceived: number
  pushes: number
}

function useGatewayStats(): GatewayStats | null {
  const [stats, setStats] = useState<GatewayStats | null>(null)
  useEffect(() => {
    let alive = true
    const tick = async () => {
      try {
        const res = await fetch('/gateway/stats')
        if (res.ok && alive) setStats((await res.json()) as GatewayStats)
      } catch {
        /* gateway down: health rows already show it */
      }
    }
    void tick()
    const id = setInterval(tick, 3000)
    return () => {
      alive = false
      clearInterval(id)
    }
  }, [])
  return stats
}

export function StatusPanel({ socket }: { socket: SocketState }) {
  const core = useHealth('core')
  const gateway = useHealth('gateway')
  const stats = useGatewayStats()
  const socketTone: Tone = socket === 'connected' ? 'ok' : socket === 'connecting' ? 'pending' : 'bad'

  return (
    <section className="card" aria-labelledby="status-title">
      <h2 id="status-title">
        System status
        <Hint>
          Health of each backend service and what it depends on, checked every 5 seconds. <b>Pushes out</b> is lower
          than <b>events in</b> because the gateway batches changes into one update every half second.
        </Hint>
      </h2>
      <ServiceBlock title="core-queue-service" probe={core} deps={['db', 'redis']} />
      <ServiceBlock title="websocket-gateway" probe={gateway} deps={['redis']} />
      <div className="status-block">
        <h3>live connection</h3>
        <ul>
          <Row label="STOMP /ws" tone={socketTone} value={socket} />
        </ul>
        {stats && (
          <dl className="fanout-stats">
            <div><dt>sockets</dt><dd>{stats.sessions}</dd></div>
            <div><dt>subscriptions</dt><dd>{stats.subscriptions}</dd></div>
            <div><dt>events in</dt><dd>{stats.eventsReceived}</dd></div>
            <div><dt>pushes out</dt><dd>{stats.pushes}</dd></div>
          </dl>
        )}
      </div>
    </section>
  )
}
