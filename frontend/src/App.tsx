import { motion } from 'motion/react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from './api/client'
import type { QueueSnapshot, ReservedRow, WaitingRow, Waitlist } from './api/types'
import { AnimatedNumber } from './components/AnimatedNumber'
import { AuthModal, IdentityChip } from './components/AuthModal'
import { ConfigCard } from './components/ConfigCard'
import { Controls } from './components/Controls'
import { ReferralFeed } from './components/ReferralFeed'
import { ServingSlots } from './components/ServingSlots'
import { StatusPanel } from './components/StatusPanel'
import { Toaster } from './components/Toaster'
import { Tracked, type TrackedEntry } from './components/Tracked'
import { WaitingList } from './components/WaitingList'
import { WaitlistTabs } from './components/WaitlistTabs'
import { usePolling } from './hooks/usePolling'
import { celebrate } from './lib/celebrate'
import { randomName } from './lib/names'
import { session, useMe } from './lib/session'
import { useLiveJson, useStomp } from './lib/stomp'
import { toast } from './lib/toast'

const POLL_MS = 1000

/** Run tasks with at most `limit` in flight. */
async function pool<T>(items: T[], limit: number, fn: (item: T) => Promise<unknown>) {
  const queue = [...items]
  await Promise.all(
    Array.from({ length: Math.min(limit, queue.length) }, async () => {
      while (queue.length) await fn(queue.shift() as T)
    }),
  )
}

function report(e: unknown) {
  if (e instanceof ApiError) {
    if (e.status === 429) toast.push('warn', 'Rate limited', `${e.message}. Retry in ${e.retryAfter ?? '?'}s.`)
    else if (e.status === 401) toast.push('error', 'Not allowed', e.message)
    else toast.push('error', e.code ?? `HTTP ${e.status}`, e.message)
  } else {
    toast.push('error', 'Something went wrong', String(e))
  }
}

const card = {
  hidden: { opacity: 0, y: 14 },
  show: (i: number) => ({ opacity: 1, y: 0, transition: { delay: 0.05 * i, type: 'spring' as const, stiffness: 260, damping: 26 } }),
}

export default function App() {
  const { state: socket } = useStomp()
  const live = socket === 'connected'
  const me = useMe()
  const [authOpen, setAuthOpen] = useState(false)
  const [waitlists, setWaitlists] = useState<Waitlist[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [tracked, setTracked] = useState<TrackedEntry[]>([])

  useEffect(() => {
    api
      .waitlists()
      .then((ws) => {
        setWaitlists(ws)
        setSelected((cur) => cur ?? ws[0]?.id ?? null)
      })
      .catch(report)
  }, [])

  const waitlist = waitlists.find((w) => w.id === selected) ?? null

  const pushed = useLiveJson<QueueSnapshot>(selected ? `/topic/waitlists/${selected}/queue` : null)
  const snapshot = usePolling(
    (signal) => (selected ? api.snapshot(selected, 50, signal) : Promise.resolve(null)),
    POLL_MS,
    [selected],
    !live,
  )
  const snap = (live && pushed) || snapshot.data

  const trackedIds = tracked.map((t) => `${t.waitlistId}/${t.entryId}`).join(',')
  usePolling(
    async () => {
      const updates = await Promise.all(
        tracked.map((t) =>
          Promise.all([
            live ? Promise.resolve(undefined) : api.position(t.waitlistId, t.entryId).catch(() => undefined),
            api.credits(t.waitlistId, t.userId).catch(() => undefined),
          ]),
        ),
      )
      setTracked((cur) =>
        cur.map((t, i) => {
          const u = updates[i]
          return u ? { ...t, latest: u[0] ?? t.latest, credits: u[1] ?? t.credits } : t
        }),
      )
      return null
    },
    live ? 3000 : POLL_MS,
    [trackedIds, live],
  )

  const feed = usePolling(
    (signal) => (selected ? api.referrals(selected, 12, signal) : Promise.resolve([])),
    2000,
    [selected],
  )

  const track = (userId: string, name: string, res: Awaited<ReturnType<typeof api.join>>) => {
    if (!selected) return
    setTracked((cur) =>
      [{ entryId: res.entryId, waitlistId: selected, userId, name, latest: res.position }, ...cur.filter((t) => t.entryId !== res.entryId)].slice(0, 8),
    )
  }

  const joinOne = useCallback(
    async (name: string, doTrack: boolean) => {
      if (!selected) return
      const user = await api.guest(name)
      const res = await api.join(selected, user.id)
      if (doTrack) track(user.id, name, res)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selected],
  )

  const onJoin = async (name: string, groupSize: number) => {
    if (!selected) return
    try {
      if (groupSize > 1) {
        const creator = await api.guest(name)
        const friends = await Promise.all(Array.from({ length: groupSize - 1 }, (_, i) => api.guest(`${name}'s friend ${i + 1}`)))
        await api.createGroup(selected, creator.id, friends.map((f) => f.id))
        toast.push('success', `${name} +${groupSize - 1} joined as a group`, waitlist?.groupPolicy === 'STRICT' ? 'One entry, one slot.' : 'Everyone gets their own entry.')
      } else {
        await joinOne(name, true)
      }
      snapshot.refresh()
    } catch (e) {
      report(e)
    }
  }

  const onJoinAsMe = async () => {
    if (!selected || !me) return
    try {
      const res = await api.join(selected, me.user.id)
      track(me.user.id, `${me.user.name} (you)`, res)
      toast.push('info', res.created ? 'You joined' : 'Already in this queue', res.position.position ? `Position #${res.position.position}` : res.position.state)
    } catch (e) {
      report(e)
    }
  }

  const onBurst = async (n: number) => {
    let failed = 0
    await pool(Array.from({ length: n }, randomName), 10, (name) =>
      joinOne(name, false).catch(() => {
        failed++
      }),
    )
    if (n > 1) toast.push(failed ? 'warn' : 'success', `${n - failed} people joined`, failed ? `${failed} failed` : undefined, 2500)
    snapshot.refresh()
  }

  const onHammer = async () => {
    if (!selected) return
    try {
      const spammer = me?.user ?? (await api.guest('Spammer'))
      const r = await api.hammer(selected, spammer.id, 30)
      toast.push(r.limited ? 'warn' : 'info', `${r.sent} requests → ${r.limited} rate-limited`,
        r.limited ? `429 Too Many Requests · Retry-After ${r.retryAfter}s` : 'Limit not reached yet')
    } catch (e) {
      report(e)
    }
  }

  const refer = async (waitlistId: string, referrerUserId: string, referrerName: string) => {
    try {
      const friendName = randomName()
      const friend = await api.guest(friendName)
      await api.join(waitlistId, friend.id, referrerUserId)
      toast.push('success', `${friendName} joined via ${referrerName}'s link`, `${referrerName} moves up ${waitlist?.bumpAmount ?? 1}`, 2500)
      feed.refresh()
    } catch (e) {
      report(e)
    }
  }

  const onConfirm = async (waitlistId: string, entryId: string, userId: string, el?: HTMLElement) => {
    try {
      await api.confirm(waitlistId, entryId, userId)
      celebrate(el)
      toast.push('success', 'Confirmed!', 'Slot released to the next person.')
    } catch (e) {
      report(e)
    }
  }

  // STRICT group: confirm the next member who hasn't yet (stand-in for each member's phone).
  const onConfirmSlot = async (r: ReservedRow, el: HTMLElement) => {
    if (!selected || !r.userId) return
    if (r.groupId && waitlist?.groupPolicy === 'STRICT') {
      try {
        const g = await api.group(selected, r.groupId)
        const next = g.members.find((m) => !m.confirmed)
        if (!next) return
        const after = await api.confirmGroupMember(selected, r.groupId, next.userId)
        const done = after.members.filter((m) => m.confirmed).length
        if (done === after.members.length) celebrate(el)
        toast.push('info', `${next.name} confirmed`, `${done}/${after.members.length} of the group`)
      } catch (e) {
        report(e)
      }
      return
    }
    await onConfirm(selected, r.entryId, r.userId, el)
  }

  const onRelease = async (r: ReservedRow) => {
    if (!selected || !r.userId) return
    try {
      await api.decline(selected, r.entryId, r.userId)
      toast.push('info', `${r.name} released the slot`)
    } catch (e) {
      report(e)
    }
  }

  const onCancel = async (entryId: string) => {
    if (!selected) return
    const row = snap?.waiting.find((w) => w.entryId === entryId)
    if (!session.canActFor(row?.userId)) {
      toast.push('warn', 'Not your entry', 'Only its owner can cancel it (you need their token).')
      return
    }
    try {
      await api.cancel(selected, entryId, row?.userId)
      snapshot.refresh()
    } catch (e) {
      report(e)
    }
  }

  const onSaveConfig = async (apiKey: string, patch: { servingCapacity: number; reservationWindowSeconds: number }) => {
    if (!selected) return
    try {
      const updated = await api.updateConfig(selected, apiKey, patch)
      setWaitlists((ws) => ws.map((w) => (w.id === updated.id ? updated : w)))
      toast.push('success', 'Config saved', `${updated.servingCapacity} slots · ${updated.reservationWindowSeconds}s window`)
    } catch (e) {
      report(e)
    }
  }

  const highlight = useMemo(() => new Set(tracked.map((t) => t.entryId)), [tracked])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <motion.span className="brand-mark" aria-hidden animate={{ rotate: [0, -8, 8, 0] }} transition={{ duration: 1.2, delay: 0.3 }}>
            ▤
          </motion.span>
          <span>WaaS</span>
          <span className="brand-sub">Waitlist-as-a-Service</span>
        </div>
        <div className="topbar-right">
          <span className={`badge ${live ? 'badge-live' : ''}`}
            title={live ? 'Updates are pushed by the WebSocket gateway' : 'Socket down: falling back to 1 s polling'}>
            {live ? 'live · push' : 'polling · 1s'}
          </span>
          <IdentityChip name={me?.user.name} onSignIn={() => setAuthOpen(true)} onSignOut={() => session.signOut()} />
        </div>
      </header>

      <main className="layout">
        <motion.section className="card queue-stage" aria-labelledby="queue-title" variants={card} initial="hidden" animate="show" custom={0}>
          <h2 id="queue-title" className="sr-only">Live queue</h2>
          <WaitlistTabs waitlists={waitlists} selected={selected} onSelect={setSelected} />

          {waitlist && (
            <div className="stats">
              <Stat label="waiting" value={snap?.queueSize} />
              <Stat label="reserved" value={snap?.reservedCount} suffix={snap ? ` / ${snap.servingCapacity}` : ''} />
              <Stat label="confirmed" value={snap?.confirmedCount} tone="ok" />
              <Stat label="expired" value={snap?.expiredCount} tone="bad" />
            </div>
          )}

          {!live && snapshot.error && <p className="alert">Queue unavailable: {snapshot.error}</p>}

          {snap && (
            <>
              <h3 className="section-label">Serving</h3>
              <ServingSlots
                capacity={snap.servingCapacity}
                reserved={snap.reserved}
                windowSeconds={waitlist?.reservationWindowSeconds ?? 600}
                onConfirm={(r, el) => void onConfirmSlot(r, el)}
                onRelease={(r) => void onRelease(r)}
                onConfirmIsPerMember={waitlist?.groupPolicy === 'STRICT'}
              />
              <h3 className="section-label">Waiting</h3>
              <WaitingList
                rows={snap.waiting}
                total={snap.queueSize}
                highlight={highlight}
                onCancel={(id) => void onCancel(id)}
                onRefer={(r: WaitingRow) => selected && r.userId && void refer(selected, r.userId, r.name)}
              />
            </>
          )}
        </motion.section>

        <aside className="side">
          <motion.div variants={card} initial="hidden" animate="show" custom={1}>
            <Controls disabled={!selected} meName={me?.user.name} onJoin={onJoin} onJoinAsMe={onJoinAsMe} onBurst={onBurst} onHammer={onHammer} />
          </motion.div>
          <motion.div variants={card} initial="hidden" animate="show" custom={2}>
            <Tracked
              entries={tracked}
              live={live}
              onForget={(id) => setTracked((cur) => cur.filter((t) => t.entryId !== id))}
              onConfirm={(t, el) => void onConfirm(t.waitlistId, t.entryId, t.userId, el)}
              onRefer={(t) => void refer(t.waitlistId, t.userId, t.name)}
            />
          </motion.div>
          {waitlist && (
            <motion.div variants={card} initial="hidden" animate="show" custom={3}>
              <ReferralFeed items={feed.data ?? []} bumpAmount={waitlist.bumpAmount} />
            </motion.div>
          )}
          {waitlist && (
            <motion.div variants={card} initial="hidden" animate="show" custom={4}>
              <ConfigCard key={`${waitlist.id}:${waitlist.servingCapacity}:${waitlist.reservationWindowSeconds}`} waitlist={waitlist} onSave={onSaveConfig} />
            </motion.div>
          )}
          <motion.div variants={card} initial="hidden" animate="show" custom={5}>
            <StatusPanel socket={socket} />
          </motion.div>
        </aside>
      </main>

      <Toaster />
      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)} />
    </div>
  )
}

function Stat({ label, value, suffix = '', tone }: { label: string; value?: number; suffix?: string; tone?: 'ok' | 'bad' }) {
  return (
    <motion.div className={`stat ${tone ? `stat-${tone}` : ''}`} whileHover={{ y: -2 }}>
      <span className="stat-value">
        {value === undefined ? '—' : <AnimatedNumber value={value} />}
        {suffix}
      </span>
      <span className="stat-label">{label}</span>
    </motion.div>
  )
}
