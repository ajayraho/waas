import { AnimatePresence, motion } from 'motion/react'
import { useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

const WIDTH = 260
const GAP = 8
const EDGE = 12

/**
 * A small "(?)" that explains the thing next to it on hover or keyboard focus.
 * The bubble is portalled to <body> with fixed coordinates, so card overflow never clips it,
 * and it is clamped to the viewport so hints near the edges stay readable.
 */
export function Hint({ children, label = 'What is this?' }: { children: ReactNode; label?: string }) {
  const id = useId()
  const ref = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number; below: boolean } | null>(null)

  const show = () => {
    const r = ref.current?.getBoundingClientRect()
    if (!r) return
    const left = Math.min(Math.max(r.left + r.width / 2 - WIDTH / 2, EDGE), window.innerWidth - WIDTH - EDGE)
    const below = r.top < 140 // not enough room above: open downwards
    setPos({ left, top: below ? r.bottom + GAP : r.top - GAP, below })
  }
  const hide = () => setPos(null)

  return (
    <>
      <button
        ref={ref}
        type="button"
        className="hint-btn"
        aria-label={label}
        aria-describedby={pos ? id : undefined}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation() // don't trigger the label / row it sits in
          show() // touch fires enter + click together, so click only opens; blur closes
        }}
      >
        ?
      </button>
      {createPortal(
        <AnimatePresence>
          {pos && (
            <motion.div
              id={id}
              role="tooltip"
              className="hint-pop"
              style={{ left: pos.left, top: pos.top, width: WIDTH, transform: pos.below ? undefined : 'translateY(-100%)' }}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0, transition: { duration: 0.1 } }}
              transition={{ duration: 0.16 }}
            >
              <motion.div className="hint-bubble" initial={{ y: pos.below ? -4 : 4 }} animate={{ y: 0 }} transition={{ duration: 0.16 }}>
                {children}
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>,
        document.body,
      )}
    </>
  )
}
