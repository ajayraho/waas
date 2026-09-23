import { motion } from 'motion/react'
import type { Waitlist } from '../api/types'

export function WaitlistTabs({
  waitlists,
  selected,
  onSelect,
}: {
  waitlists: Waitlist[]
  selected: string | null
  onSelect: (id: string) => void
}) {
  return (
    <div className="tabs" role="tablist" aria-label="Waitlists">
      {waitlists.map((w) => (
        <button key={w.id} role="tab" aria-selected={w.id === selected} className="tab" onClick={() => onSelect(w.id)}>
          {w.id === selected && (
            <motion.span layoutId="tab-highlight" className="tab-highlight" transition={{ type: 'spring', stiffness: 450, damping: 34 }} />
          )}
          <span className="tab-name">{w.name}</span>
          <span className="tab-meta">
            {w.groupPolicy.toLowerCase()} · {w.servingCapacity} slot{w.servingCapacity === 1 ? '' : 's'}
          </span>
        </button>
      ))}
    </div>
  )
}
