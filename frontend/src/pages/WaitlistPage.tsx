import { AnimatePresence, motion } from 'motion/react'
import { useCallback, useEffect, useMemo, useState, type Dispatch, type SetStateAction } from 'react'
import { api } from '../api/client'
import type { QueueSnapshot, ReservedRow, WaitingRow, Waitlist } from '../api/types'
import { ConfigCard } from '../components/ConfigCard'
import { Controls } from '../components/Controls'
import { Hint } from '../components/Hint'
import { IntegrateCard } from '../components/IntegrateCard'
import { ModeSwitch } from '../components/ModeSwitch'
import { ReferralFeed } from '../components/ReferralFeed'
import { ServingSlots } from '../components/ServingSlots'
import { Stat } from '../components/Stat'
import { StatusPanel } from '../components/StatusPanel'
import { Tracked, type TrackedEntry } from '../components/Tracked'
import { WaitingList } from '../components/WaitingList'
import { usePolling } from '../hooks/usePolling'
import { useThroughput } from '../hooks/useThroughput'
import { celebrate } from '../lib/celebrate'
import { report } from '../lib/report'
import { useMode } from '../lib/mode'
import { randomName } from '../lib/names'
import { navigate } from '../lib/router'
import { session, useMe } from '../lib/session'
import type { SocketState } from '../lib/stomp'
import { useLiveJson } from '../lib/stomp'
import { toast } from '../lib/toast'
import { seenCards } from '../lib/waitlistCache'

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

// side panel: children stagger in when the mode changes, the whole panel fades out
const side = {
  hidden: {},
  show: { transition: { staggerChildren: 0.06 } },
  exit: { opacity: 0, x: 12, transition: { duration: 0.15 } },
}
const card = {
  hidden: { opacity: 0, x: 18 },
  show: { opacity: 1, x: 0, transition: { type: 'spring' as const, stiffness: 300, damping: 28 } },
}

/** One waitlist's live queue: /w/:id */
export function WaitlistPage({ waitlistId, socket, tracked, setTracked }: {
  waitlistId: string
  socket: SocketState
  tracked: TrackedEntry[]
  setTracked: Dispatch<SetStateAction<TrackedEntry[]>>
}) {
  const live = socket === 'connected'
  const me = useMe()
  const [mode, setMode] = useMode()
  const seen = seenCards.get(waitlistId) // from the directory, if we came from there
  const [waitlist, setWaitlist] = useState<Waitlist | null>(null)
  const [missing, setMissing] = useState(false)
  const selected = waitlistId

  useEffect(() => {
    const ctrl = new AbortController()
    api
      .waitlist(waitlistId, ctrl.signal)
      .then(setWaitlist)
      .catch((e) => {
        if (ctrl.signal.aborted) return
        setMissing(true)
        report(e)
      })
    return () => ctrl.abort()
  }, [waitlistId])

  const name = waitlist?.name ?? seen?.name
  const description = waitlist?.description ?? seen?.description

  const pushed = useLiveJson<QueueSnapshot>(`/topic/waitlists/${selected}/queue`)
  const snapshot = usePolling((signal) => api.snapshot(selected, 50, signal), POLL_MS, [selected], !live)
  const snap = (live && pushed) || snapshot.data
  const throughput = useThroughput(selected, snap, waitlist?.reservationWindowSeconds ?? 600)

  const feed = usePolling((signal) => api.referrals(selected, 12, signal), 2000, [selected])

  const track = (userId: string, name: string, res: Awaited<ReturnType<typeof api.join>>) => {
    setTracked((cur) =>
      [{ entryId: res.entryId, waitlistId: selected, userId, name, latest: res.position }, ...cur.filter((t) => t.entryId !== res.entryId)].slice(0, 8),
    )
  }

  const joinOne = useCallback(
    async (name: string, doTrack: boolean) => {
      const user = await api.guest(name)
      const res = await api.join(selected, user.id)
      if (doTrack) track(user.id, name, res)
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selected],
  )

  const onJoin = async (name: string, groupSize: number) => {
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
    if (!me) return
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
    try {
      const spammer = me?.user ?? (await api.guest('Spammer'))
      const r = await api.hammer(selected, spammer.id, 30)
      toast.push(r.limited ? 'warn' : 'info', `${r.sent} requests → ${r.limited} rate-limited`,
        r.limited ? `429 Too Many Requests · Retry-After ${r.retryAfter}s` : 'Limit not reached yet')
    } catch (e) {
      report(e)
    }
  }

  const refer = async (referrerUserId: string, referrerName: string) => {
    try {
      const friendName = randomName()
      const friend = await api.guest(friendName)
      await api.join(selected, friend.id, referrerUserId)
      toast.push('success', `${friendName} joined via ${referrerName}'s link`, `${referrerName} moves up ${waitlist?.bumpAmount ?? 1}`, 2500)
      feed.refresh()
    } catch (e) {
      report(e)
    }
  }

  const onConfirm = async (entryId: string, userId: string, el?: HTMLElement) => {
    try {
      await api.confirm(selected, entryId, userId)
      celebrate(el)
      toast.push('success', 'Confirmed!', 'Slot released to the next person.')
    } catch (e) {
      report(e)
    }
  }

  // STRICT group: confirm the next member who hasn't yet (stand-in for each member's phone).
  const onConfirmSlot = async (r: ReservedRow, el: HTMLElement) => {
    if (!r.userId) return
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
    await onConfirm(r.entryId, r.userId, el)
  }

  const onRelease = async (r: ReservedRow) => {
    if (!r.userId) return
    try {
      await api.decline(selected, r.entryId, r.userId)
      toast.push('info', `${r.name} released the slot`)
    } catch (e) {
      report(e)
    }
  }

  const onCancel = async (entryId: string) => {
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
    try {
      const updated = await api.updateConfig(selected, apiKey, patch)
      setWaitlist(updated)
      toast.push('success', 'Config saved', `${updated.servingCapacity} slots · ${updated.reservationWindowSeconds}s window`)
    } catch (e) {
      report(e)
    }
  }

  // "Your people" on this page = the people tracked on this waitlist
  const mine = useMemo(() => tracked.filter((t) => t.waitlistId === selected), [tracked, selected])
  const highlight = useMemo(() => new Set(mine.map((t) => t.entryId)), [mine])

  if (missing && !name) {
    return (
      <div className="not-found">
        <p className="display">no such waitlist</p>
        <button className="link-btn" onClick={() => navigate('/')}>← all waitlists</button>
      </div>
    )
  }

  return (
    <>
      <section className="hero" aria-labelledby="hero-title">
        <div className="page-nav">
          <motion.button className="back-link" onClick={() => navigate('/')} whileHover={{ x: -3 }}>
            ← all waitlists
          </motion.button>
          {waitlist && (
            <motion.span className="hero-meta" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              {waitlist.groupPolicy.toLowerCase()} groups · {waitlist.servingCapacity} slot{waitlist.servingCapacity === 1 ? '' : 's'} · bump {waitlist.bumpAmount}
              <Hint>
                <b>{waitlist.groupPolicy.toLowerCase()} groups</b>:{' '}
                {waitlist.groupPolicy === 'STRICT'
                  ? 'friends who join together hold one place and get one slot together.'
                  : 'friends who join together each get their own place and move on their own.'}{' '}
                <b>Slots</b>: how many people can hold a turn at once. <b>Bump</b>: places someone moves up for each friend they refer.
              </Hint>
            </motion.span>
          )}
        </div>

        <div className="hero-band">
          <div className="hero-title">
            <motion.h1 id="hero-title" layoutId={`title-${waitlistId}`} className="display"
              transition={{ type: 'spring', stiffness: 170, damping: 24 }}>
              {name ?? ' '}
            </motion.h1>
            {description && (
              <motion.p className="hero-sub" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.15 }}>
                {description}
              </motion.p>
            )}
          </div>
          <div className="hero-stats">
            <Stat label="waiting" value={snap?.queueSize ?? seen?.waiting} layoutId={`waiting-${waitlistId}`}
              hint="People in line who haven't had their turn yet." />
            <Stat label="being served" value={snap?.reservedCount ?? seen?.reserved}
              suffix={snap ? ` / ${snap.servingCapacity}` : seen ? ` / ${seen.servingCapacity}` : ''}
              hint="People holding a slot right now, out of the total number of slots. Each one is on a countdown to confirm." />
            <Stat label="confirmed" value={snap?.confirmedCount} tone="ok"
              hint="People who used their turn (bought, claimed, got in). They're done, and their slot went to the next person." />
            <Stat label="expired" value={snap?.expiredCount} tone="bad"
              hint="People whose countdown ran out before they confirmed. They lost their turn and the slot moved on." />
          </div>
        </div>
      </section>

      <motion.main className="layout" initial={{ opacity: 0, y: 28 }} animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.18, type: 'spring', stiffness: 160, damping: 24 }}>
        <section className="queue-stage" aria-label="Live queue">
          {!live && snapshot.error && <p className="alert">Queue unavailable: {snapshot.error}</p>}

          {snap && (
            <>
              <h3 className="section-label">
                Serving
                <Hint>
                  The front of the line. Each slot is one turn in progress: the person has until the ring runs out to
                  confirm. <b>Confirm</b> (done) or <b>Release</b> (give it up) frees the slot, and the next person in line
                  moves in automatically. Only the person holding the slot can confirm it.
                </Hint>
              </h3>
              <ServingSlots
                capacity={snap.servingCapacity}
                reserved={snap.reserved}
                windowSeconds={waitlist?.reservationWindowSeconds ?? 600}
                onConfirm={(r, el) => void onConfirmSlot(r, el)}
                onRelease={(r) => void onRelease(r)}
                onConfirmIsPerMember={waitlist?.groupPolicy === 'STRICT'}
              />
              <h3 className="section-label">
                Waiting
                <Hint>
                  Everyone behind the slots, in order. <b>↑</b>: a new friend joins through this person&apos;s referral
                  link, and they move up. <b>×</b>: they leave the line.
                </Hint>
              </h3>
              <WaitingList
                rows={snap.waiting}
                total={snap.queueSize}
                highlight={highlight}
                internals={mode === 'internals'}
                capacity={snap.servingCapacity}
                throughput={throughput}
                meId={me?.user.id}
                onCancel={(id) => void onCancel(id)}
                onRefer={(r: WaitingRow) => r.userId && void refer(r.userId, r.name)}
              />
            </>
          )}
        </section>

        <aside className="side">
          <ModeSwitch mode={mode} onChange={setMode} />
          <AnimatePresence mode="wait" initial={false}>
            <motion.div key={mode} className="side-inner" variants={side} initial="hidden" animate="show" exit="exit">
              {mode === 'queue' && (
                <>
                  <motion.div variants={card}>
                    <Controls part="join" disabled={false} meName={me?.user.name} onJoin={onJoin} onJoinAsMe={onJoinAsMe} onBurst={onBurst} onHammer={onHammer} />
                  </motion.div>
                  <motion.div variants={card}>
                    <Tracked
                      entries={mine}
                      live={live}
                      onForget={(id) => setTracked((cur) => cur.filter((t) => t.entryId !== id))}
                      onConfirm={(t, el) => void onConfirm(t.entryId, t.userId, el)}
                      onRefer={(t) => void refer(t.userId, t.name)}
                    />
                  </motion.div>
                </>
              )}
              {mode === 'console' && (
                <>
                  <motion.div variants={card}>
                    <Controls part="load" disabled={false} meName={me?.user.name} onJoin={onJoin} onJoinAsMe={onJoinAsMe} onBurst={onBurst} onHammer={onHammer} />
                  </motion.div>
                  {waitlist && (
                    <motion.div variants={card}>
                      <ConfigCard key={`${waitlist.id}:${waitlist.servingCapacity}:${waitlist.reservationWindowSeconds}`} waitlist={waitlist} onSave={onSaveConfig} />
                    </motion.div>
                  )}
                  <motion.div variants={card}>
                    <IntegrateCard waitlistId={waitlistId} />
                  </motion.div>
                </>
              )}
              {mode === 'internals' && (
                <>
                  <motion.div variants={card}>
                    <ReferralFeed items={feed.data ?? []} bumpAmount={waitlist?.bumpAmount ?? 1} />
                  </motion.div>
                  <motion.div variants={card}>
                    <StatusPanel socket={socket} />
                  </motion.div>
                </>
              )}
            </motion.div>
          </AnimatePresence>
        </aside>
      </motion.main>
    </>
  )
}
