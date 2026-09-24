import { motion } from 'motion/react'
import { MODES, type Mode } from '../lib/mode'

export function ModeSwitch({ mode, onChange }: { mode: Mode; onChange: (m: Mode) => void }) {
  return (
    <nav className="modes" aria-label="View">
      {MODES.map((m, i) => (
        <span key={m.id} className="modes-item">
          {i > 0 && <span className="modes-sep" aria-hidden>·</span>}
          <button className="mode" aria-current={mode === m.id ? 'page' : undefined} title={m.blurb} onClick={() => onChange(m.id)}>
            {m.id}
            {mode === m.id && <motion.span layoutId="mode-underline" className="mode-underline" transition={{ type: 'spring', stiffness: 500, damping: 36 }} />}
          </button>
        </span>
      ))}
    </nav>
  )
}
