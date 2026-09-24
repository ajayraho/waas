import { motion } from 'motion/react'
import { AnimatedNumber } from './AnimatedNumber'
import { Hint } from './Hint'

/**
 * "( 14 )  waiting". `layoutId` lets the number fly between pages: the directory row and the
 * waitlist page render the same count with the same id, and motion animates one into the other.
 */
export function Stat({ label, value, suffix = '', tone, hint, layoutId }: {
  label: string
  value?: number
  suffix?: string
  tone?: 'ok' | 'bad'
  hint?: string
  layoutId?: string
}) {
  return (
    <div className={`stat ${tone ? `stat-${tone}` : ''}`}>
      <motion.span layoutId={layoutId} className="stat-value">
        {value === undefined ? '—' : <AnimatedNumber value={value} />}
        {suffix}
      </motion.span>
      <span className="stat-label">
        {label}
        {hint && <Hint>{hint}</Hint>}
      </span>
    </div>
  )
}
