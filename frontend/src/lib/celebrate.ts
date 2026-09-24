import confetti from 'canvas-confetti'

// the palette's colors: ice blue, paper, sand, sage, lavender, ink
const COLORS = ['#a9c3dc', '#dce4ec', '#d9b77e', '#8ec6a8', '#b4b8e8', '#2c4863']

/** Confetti from an element (or the middle of the screen). */
export function celebrate(from?: Element | null) {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  const r = from?.getBoundingClientRect()
  const origin = r
    ? { x: (r.left + r.width / 2) / window.innerWidth, y: (r.top + r.height / 2) / window.innerHeight }
    : { x: 0.5, y: 0.4 }
  confetti({ particleCount: 90, spread: 70, startVelocity: 38, origin, scalar: 0.9, colors: COLORS })
  setTimeout(() => confetti({ particleCount: 40, spread: 100, origin, scalar: 0.7, colors: COLORS }), 180)
}
