import { AnimatePresence, motion } from 'motion/react'
import type { ReservedRow } from '../api/types'
import { useNow } from '../hooks/usePolling'
import { countdown } from '../lib/format'
import { session } from '../lib/session'

const R = 26
const CIRC = 2 * Math.PI * R

function Ring({ fraction, lapsed }: { fraction: number; lapsed: boolean }) {
  // indigo (calm) -> amber (act soon) -> orange (last quarter); red only once it has lapsed
  const stage = fraction < 0.25 ? 'ring-urgent' : fraction < 0.5 ? 'ring-mid' : ''
  return (
    <svg className={`ring ${stage} ${lapsed ? 'ring-lapsed' : ''}`} viewBox="0 0 64 64" aria-hidden>
      <circle className="ring-track" cx="32" cy="32" r={R} />
      <circle className="ring-fill" cx="32" cy="32" r={R} strokeDasharray={CIRC} strokeDashoffset={CIRC * (1 - fraction)} />
    </svg>
  )
}

/** Serving capacity made visible: C slots, each free or held by a RESERVED entry with its countdown. */
export function ServingSlots({
  capacity,
  reserved,
  windowSeconds,
  onConfirm,
  onRelease,
  onConfirmIsPerMember = false,
}: {
  capacity: number
  reserved: ReservedRow[]
  windowSeconds: number
  onConfirm: (r: ReservedRow, el: HTMLElement) => void
  onRelease: (r: ReservedRow) => void
  onConfirmIsPerMember?: boolean
}) {
  const now = useNow()
  const count = Math.max(capacity, reserved.length)

  return (
    <div className="slots" aria-label="Serving slots">
      {Array.from({ length: count }, (_, i) => {
        const r = reserved[i]
        return (
          <div key={i} className="slot-cell">
            <AnimatePresence mode="popLayout" initial={false}>
              {!r ? (
                <motion.div key="free" className="slot slot-free" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  <span className="slot-label">slot {i + 1}</span>
                  <span className="slot-free-text">free</span>
                </motion.div>
              ) : (
                <SlotCard key={r.entryId} r={r} i={i} now={now} windowSeconds={windowSeconds}
                  onConfirm={onConfirm} onRelease={onRelease} perMember={onConfirmIsPerMember} />
              )}
            </AnimatePresence>
          </div>
        )
      })}
    </div>
  )
}

function SlotCard({ r, i, now, windowSeconds, onConfirm, onRelease, perMember }: {
  r: ReservedRow; i: number; now: number; windowSeconds: number
  onConfirm: (r: ReservedRow, el: HTMLElement) => void; onRelease: (r: ReservedRow) => void; perMember: boolean
}) {
  const left = new Date(r.expiresAt).getTime() - now
  const lapsed = left <= 0
  const fraction = Math.max(0, Math.min(1, left / (windowSeconds * 1000)))
  const canAct = session.canActFor(r.userId) && !lapsed

  return (
    <motion.div
      className={`slot slot-held ${lapsed ? 'slot-lapsed' : ''}`}
      initial={{ opacity: 0, scale: 0.85, y: 16 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      exit={{ opacity: 0, scale: 0.9, y: -12, transition: { duration: 0.25 } }}
      transition={{ type: 'spring', stiffness: 320, damping: 24 }}
    >
      <div className="slot-top">
        <div className="slot-info">
          <span className="slot-label">slot {i + 1} · {lapsed ? 'lapsed' : 'reserved'}</span>
          <span className="slot-name">
            {r.name}
            {r.groupSize > 0 && <span className="muted small-tag"> +{r.groupSize - 1}</span>}
          </span>
          {r.groupSize > 0 && perMember && (
            <span className="muted small-tag">{r.groupConfirmed}/{r.groupSize} confirmed</span>
          )}
        </div>
        <div className="ring-wrap">
          <Ring fraction={fraction} lapsed={lapsed} />
          <span className="ring-text">{lapsed ? '…' : countdown(left)}</span>
        </div>
      </div>
      {canAct ? (
        <span className="slot-actions">
          <motion.button whileTap={{ scale: 0.94 }} whileHover={{ y: -1 }} className="btn btn-sm btn-ok"
            onClick={(e) => onConfirm(r, e.currentTarget)}>
            Confirm
          </motion.button>
          <motion.button whileTap={{ scale: 0.94 }} className="btn btn-sm" onClick={() => onRelease(r)}>
            Release
          </motion.button>
        </span>
      ) : (
        !lapsed && <span className="slot-note">only {r.name.split(' ')[0]} can confirm</span>
      )}
    </motion.div>
  )
}
