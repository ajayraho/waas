import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { WaitingRow } from '../api/types'
import { score, shortId } from '../lib/format'

type Bumps = Record<string, { n: number; spots: number }>

/**
 * Rows slide in, glide to new places (layout animation) and slide out.
 * A row whose *score* dropped was bumped by a referral: it gets a green flash and a floating "↑N".
 */
export function WaitingList({
  rows,
  total,
  highlight,
  onCancel,
  onRefer,
}: {
  rows: WaitingRow[]
  total: number
  highlight: Set<string>
  onCancel: (entryId: string) => void
  onRefer: (row: WaitingRow) => void
}) {
  // "Previous props in state": compare during render, no effect needed.
  const [prev, setPrev] = useState(rows)
  const [bumps, setBumps] = useState<Bumps>({})
  if (rows !== prev) {
    const before = new Map(prev.map((r) => [r.entryId, r]))
    const next: Bumps = { ...bumps }
    for (const r of rows) {
      const old = before.get(r.entryId)
      if (old && r.score < old.score) {
        next[r.entryId] = { n: (bumps[r.entryId]?.n ?? 0) + 1, spots: Math.max(1, old.position - r.position) }
      }
    }
    setPrev(rows)
    setBumps(next)
  }

  if (rows.length === 0) {
    return (
      <motion.p className="muted list-empty" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
        Nobody is waiting. Add people with the controls.
      </motion.p>
    )
  }
  return (
    <>
      <table className="queue-table">
        <thead>
          <tr>
            <th className="num">#</th>
            <th>name</th>
            <th className="num" title="Redis ZSET score: lower is closer to the front">score</th>
            <th className="col-entry">entry</th>
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          <AnimatePresence initial={false}>
            {rows.map((r) => {
              const bump = bumps[r.entryId]
              return (
                <motion.tr
                  key={r.entryId}
                  layout="position"
                  initial={{ opacity: 0, x: -24 }}
                  animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 40, transition: { duration: 0.2 } }}
                  transition={{ type: 'spring', stiffness: 500, damping: 40 }}
                  className={highlight.has(r.entryId) ? 'row-tracked' : undefined}
                >
                  <td className="num pos">
                    <motion.span key={r.position} initial={{ y: -6, opacity: 0.4 }} animate={{ y: 0, opacity: 1 }} style={{ display: 'inline-block' }}>
                      {r.position}
                    </motion.span>
                  </td>
                  <td className="name-cell">
                    {bump ? (
                      <motion.span key={bump.n} className="name-pill" initial={{ backgroundColor: 'rgba(43,138,62,0.35)' }}
                        animate={{ backgroundColor: 'rgba(43,138,62,0)' }} transition={{ duration: 1.6 }}>
                        {r.name}
                      </motion.span>
                    ) : (
                      <span className="name-pill">{r.name}</span>
                    )}
                    {r.groupSize > 0 && <span className="muted small-tag"> · group of {r.groupSize}</span>}
                    {bump && (
                      <motion.span key={`b${bump.n}`} className="bump-badge" initial={{ opacity: 1, y: 6, scale: 0.7 }}
                        animate={{ opacity: 0, y: -22, scale: 1.15 }} transition={{ duration: 1.5, ease: 'easeOut' }}>
                        ↑{bump.spots}
                      </motion.span>
                    )}
                  </td>
                  <td className="num mono muted">{score(r.score)}</td>
                  <td className="mono muted col-entry">{shortId(r.entryId)}</td>
                  <td className="actions">
                    {r.userId && (
                      <motion.button whileHover={{ scale: 1.2 }} whileTap={{ scale: 0.9 }} className="icon-btn icon-refer"
                        title={`A new friend joins through ${r.name}'s referral link`} aria-label={`Refer a friend for ${r.name}`}
                        onClick={() => onRefer(r)}>
                        ↑
                      </motion.button>
                    )}
                    <motion.button whileHover={{ scale: 1.2 }} whileTap={{ scale: 0.9 }} className="icon-btn"
                      title="Cancel (WAITING → CANCELLED)" onClick={() => onCancel(r.entryId)}>
                      ×
                    </motion.button>
                  </td>
                </motion.tr>
              )
            })}
          </AnimatePresence>
        </tbody>
      </table>
      {total > rows.length && <p className="muted list-more">+ {total - rows.length} more waiting</p>}
    </>
  )
}
