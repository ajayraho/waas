/** mm:ss for a countdown; negative values render as 0:00. */
export function countdown(ms: number) {
  const s = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** Scores are doubles; show integers plainly and midpoints (3.5) with their fraction. */
export function score(n: number) {
  return Number.isInteger(n) ? String(n) : n.toFixed(3).replace(/0+$/, '')
}

export const shortId = (id: string) => id.slice(0, 8)
