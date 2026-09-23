import { AnimatePresence, motion } from 'motion/react'
import { toast, useToasts } from '../lib/toast'

const ICON = { info: 'ℹ', success: '✓', warn: '!', error: '×', turn: '★' } as const

export function Toaster() {
  const items = useToasts()
  return (
    <div className="toaster" aria-live="polite">
      <AnimatePresence initial={false}>
        {items.map((t) => (
          <motion.div
            key={t.id}
            layout
            className={`toast toast-${t.kind}`}
            initial={{ opacity: 0, x: 60, scale: 0.9 }}
            animate={{ opacity: 1, x: 0, scale: 1 }}
            exit={{ opacity: 0, x: 80, transition: { duration: 0.2 } }}
            transition={{ type: 'spring', stiffness: 420, damping: 30 }}
            onClick={() => toast.dismiss(t.id)}
          >
            <span className="toast-icon">{ICON[t.kind]}</span>
            <div className="toast-text">
              <strong>{t.title}</strong>
              {t.body && <span>{t.body}</span>}
            </div>
            <motion.span
              className="toast-timer"
              initial={{ scaleX: 1 }}
              animate={{ scaleX: 0 }}
              transition={{ duration: t.ms / 1000, ease: 'linear' }}
            />
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  )
}
