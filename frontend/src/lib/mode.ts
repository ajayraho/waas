import { useEffect, useState } from 'react'

export type Mode = 'queue' | 'console' | 'internals'
export const MODES: { id: Mode; blurb: string }[] = [
  { id: 'queue', blurb: 'What a customer sees: the line, the slots, your people' },
  { id: 'console', blurb: 'What the business uses: load tools and waitlist settings' },
  { id: 'internals', blurb: 'What the engineer looks at: scores, referral audit, service health' },
]

function fromHash(): Mode {
  const h = window.location.hash.slice(1)
  return MODES.some((m) => m.id === h) ? (h as Mode) : 'queue'
}

/** Current mode, kept in the URL hash so a refresh (or a shared link) lands on the same view. */
export function useMode(): [Mode, (m: Mode) => void] {
  const [mode, setMode] = useState<Mode>(fromHash)
  useEffect(() => {
    const onHash = () => setMode(fromHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  const set = (m: Mode) => {
    window.history.replaceState(null, '', m === 'queue' ? window.location.pathname : `#${m}`)
    setMode(m)
  }
  return [mode, set]
}
