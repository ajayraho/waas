import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef } from 'react'
import type { CreditLedger, EntryPosition } from '../api/types'
import { useNow } from '../hooks/usePolling'
import { celebrate } from '../lib/celebrate'
import { countdown } from '../lib/format'
import { useLiveJson } from '../lib/stomp'
import { toast } from '../lib/toast'

export interface TrackedEntry {
  entryId: string
  waitlistId: string
  userId: string
  name: string
  latest?: EntryPosition
  credits?: CreditLedger
}

type Actions = {
  onForget: (id: string) => void
  onConfirm: (t: TrackedEntry, el: HTMLElement) => void
  onRefer: (t: TrackedEntry) => void
}

/** What each person's phone would show: their own pushed position, countdown and credits. */
export function Tracked({ entries, live, ...actions }: { entries: TrackedEntry[]; live: boolean } & Actions) {
  return (
    <section className="card" aria-labelledby="tracked-title">
      <h2 id="tracked-title">Your people</h2>
      {entries.length === 0 ? (
        <p className="muted small">People you join by name show up here with their live position.</p>
      ) : (
        <ul className="tracked">
          <AnimatePresence initial={false}>
            {entries.map((t) => (
              <TrackedRow key={t.entryId} t={t} live={live} {...actions} />
            ))}
          </AnimatePresence>
        </ul>
      )}
    </section>
  )
}

function TrackedRow({ t, live, onForget, onConfirm, onRefer }: { t: TrackedEntry; live: boolean } & Actions) {
  const now = useNow()
  const pushed = useLiveJson<EntryPosition>(live ? `/topic/waitlists/${t.waitlistId}/entries/${t.entryId}` : null)
  const p = (live && pushed) || t.latest
  const c = t.credits
  const rowRef = useRef<HTMLLIElement>(null)

  // Toast / confetti when this person's state changes (not on first render).
  const lastState = useRef(p?.state)
  const state = p?.state
  useEffect(() => {
    const prev = lastState.current
    lastState.current = state
    if (!prev || !state || prev === state) return
    if (state === 'RESERVED') toast.push('turn', `${t.name}: it's your turn!`, 'A slot opened. Confirm before the ring runs out.', 6000)
    if (state === 'CONFIRMED') celebrate(rowRef.current)
    if (state === 'EXPIRED') toast.push('warn', `${t.name} missed the window`, 'The slot went to the next person.')
  }, [state, t.name])

  let detail = '…'
  if (p?.state === 'WAITING') detail = p.position != null ? `#${p.position} of ${p.queueSize}` : 'waiting (resyncing)'
  else if (p?.state === 'RESERVED' && p.expiresAt) detail = `your turn · ${countdown(new Date(p.expiresAt).getTime() - now)}`
  else if (p) detail = p.state.toLowerCase()

  return (
    <motion.li
      ref={rowRef}
      layout
      className={`tracked-item ${p?.state === 'RESERVED' ? 'tracked-turn' : ''}`}
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: 0 }}
    >
      <div className="tracked-row">
        <motion.span key={p?.state} className={`state-pill state-${(p?.state ?? 'WAITING').toLowerCase()}`}
          initial={{ scale: 0.6 }} animate={{ scale: 1 }} transition={{ type: 'spring', stiffness: 500, damping: 18 }}>
          {p?.state ?? '…'}
        </motion.span>
        <span className="tracked-name">{t.name}</span>
        <motion.span key={detail.split(' ')[0]} className="tracked-detail mono" initial={{ y: -6, opacity: 0 }} animate={{ y: 0, opacity: 1 }}>
          {detail}
        </motion.span>
        {p?.state === 'RESERVED' && (
          <motion.button whileTap={{ scale: 0.92 }} className="btn btn-sm btn-ok" onClick={(e) => onConfirm(t, e.currentTarget)}>
            Confirm
          </motion.button>
        )}
        <button className="icon-btn" title="Stop tracking" onClick={() => onForget(t.entryId)}>
          ×
        </button>
      </div>
      <div className="tracked-sub">
        <span className="muted">
          {c && c.earned > 0
            ? `credits ${c.earned} · used ${c.applied}${c.pending ? ` · banked ${c.pending}` : ''}${c.rejected ? ` · ${c.rejected} blocked` : ''}`
            : 'no referrals yet'}
        </span>
        <button className="link-btn" onClick={() => onRefer(t)}>
          Refer a friend
        </button>
      </div>
    </motion.li>
  )
}
