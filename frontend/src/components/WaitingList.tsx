import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { WaitingRow } from '../api/types'
import type { Throughput } from '../hooks/useThroughput'
import { useNow } from '../hooks/usePolling'
import { ago, duration, score, shortId } from '../lib/format'
import { Hint } from './Hint'

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
  internals = false,
  capacity,
  throughput,
  meId,
}: {
  rows: WaitingRow[]
  total: number
  highlight: Set<string>
  onCancel: (entryId: string) => void
  onRefer: (row: WaitingRow) => void
  /** show the engineer columns (ZSET score, entry id) */
  internals?: boolean
  /** serving slots: the first `capacity` rows are next in line */
  capacity: number
  throughput: Throughput | null
  meId?: string
}) {
  const now = useNow(10_000)
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
            <th className="col-group">group</th>
            <th className="col-boost">
              referrals
              <Hint>
                How this person moved up by inviting friends. <b>↑4 · 2</b> means 2 friends joined through their link
                and they gained 4 places. Credits that couldn&apos;t be used (nobody passes #1) don&apos;t show here.
              </Hint>
            </th>
            <th className="col-joined">joined</th>
            <th className="num col-eta">
              est. wait
              <Hint>
                {throughput?.measured
                  ? `Measured from the live line: about ${throughput.perMinute.toFixed(1)} turns finish per minute right now. Position ÷ that rate = the wait. It's an estimate, not a promise.`
                  : 'Not enough history yet, so this is the worst case: every slot uses its full checkout window. It switches to a measured estimate once a few people have confirmed or expired.'}
              </Hint>
            </th>
            {internals && (
              <th className="num">
                score
                <Hint>
                  The sort key in the Redis sorted set. Lower means closer to the front. It starts as the join order;
                  a referral subtracts from it, which is how someone jumps ahead.
                </Hint>
              </th>
            )}
            {internals && <th className="col-entry">entry</th>}
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
                      <motion.span key={bump.n} className="name-pill" initial={{ backgroundColor: 'rgba(120,126,200,0.32)' }}
                        animate={{ backgroundColor: 'rgba(120,126,200,0)' }} transition={{ duration: 1.6 }}>
                        {r.name}
                      </motion.span>
                    ) : (
                      <span className="name-pill">{r.name}</span>
                    )}
                    {r.userId && r.userId === meId ? (
                      <span className="row-tag tag-you">you</span>
                    ) : (
                      highlight.has(r.entryId) && <span className="row-tag tag-yours">yours</span>
                    )}
                    {r.position <= capacity && <span className="row-tag tag-next">next</span>}
                    {bump && (
                      <motion.span key={`b${bump.n}`} className="bump-badge" initial={{ opacity: 1, y: 6, scale: 0.7 }}
                        animate={{ opacity: 0, y: -22, scale: 1.15 }} transition={{ duration: 1.5, ease: 'easeOut' }}>
                        ↑{bump.spots}
                      </motion.span>
                    )}
                  </td>
                  <td className="col-group">
                    {r.groupSize > 1 && <GroupDots name={r.name} size={r.groupSize} />}
                  </td>
                  <td className="col-boost">
                    {r.boost > 0 || r.referrals > 0 ? (
                      <span className="boost mono">
                        ↑{r.boost}
                        <span className="muted"> · {r.referrals}</span>
                      </span>
                    ) : (
                      <span className="faint">—</span>
                    )}
                  </td>
                  <td className="col-joined muted">{r.joinedAt ? ago(r.joinedAt, now) : '—'}</td>
                  <td className="num col-eta mono">
                    {throughput ? (
                      <span className={throughput.measured ? '' : 'muted'}>
                        {eta(r.position / throughput.perMinute, throughput.measured)}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                  {internals && <td className="num mono muted">{score(r.score)}</td>}
                  {internals && <td className="mono muted col-entry">{shortId(r.entryId)}</td>}
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

/** A group as a little stack of circles: the owner's initial first, then one per extra member. */
function GroupDots({ name, size }: { name: string; size: number }) {
  const shown = Math.min(size, 4)
  return (
    <span className="group-dots" title={`${name} + ${size - 1} friend${size === 2 ? '' : 's'}`}>
      {Array.from({ length: shown }, (_, i) => (
        <span key={i} className="group-dot">{i === 0 ? name.slice(0, 1).toLowerCase() : ''}</span>
      ))}
      <span className="group-count mono">{size}</span>
    </span>
  )
}

/** "~12 min" when measured, "≤ 12 min" when it's the worst case; "< 1 min" needs no prefix. */
function eta(minutes: number, measured: boolean) {
  const d = duration(minutes)
  return d.startsWith('<') ? d : `${measured ? '~' : '≤'} ${d}`
}
