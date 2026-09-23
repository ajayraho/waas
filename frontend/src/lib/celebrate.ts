import confetti from 'canvas-confetti'

/** Confetti from an element (or the middle of the screen). */
export function celebrate(from?: Element | null) {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  const r = from?.getBoundingClientRect()
  const origin = r
    ? { x: (r.left + r.width / 2) / window.innerWidth, y: (r.top + r.height / 2) / window.innerHeight }
    : { x: 0.5, y: 0.4 }
  confetti({ particleCount: 90, spread: 70, startVelocity: 38, origin, scalar: 0.9 })
  setTimeout(() => confetti({ particleCount: 40, spread: 100, origin, scalar: 0.7 }), 180)
}
