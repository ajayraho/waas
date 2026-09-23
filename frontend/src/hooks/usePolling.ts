import { useEffect, useRef, useState } from 'react'

/**
 * Poll an async loader while the tab is visible.
 *
 * Since Brick 5 the queue and positions arrive by WebSocket push; polling is the fallback
 * while the socket is down (graceful degradation), plus slow-changing reads (ledger, feed).
 */
export function usePolling<T>(
  load: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  deps: unknown[],
  enabled = true,
) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  })
  const [tick, setTick] = useState(0)

  useEffect(() => {
    if (!enabled) return
    let alive = true
    const ctrl = new AbortController()
    const run = async () => {
      if (document.visibilityState !== 'visible') return
      try {
        const next = await loadRef.current(ctrl.signal)
        if (alive) {
          setData(next)
          setError(null)
        }
      } catch (e) {
        if (alive && !ctrl.signal.aborted) setError(e instanceof Error ? e.message : 'failed')
      }
    }
    void run()
    const id = setInterval(run, intervalMs)
    return () => {
      alive = false
      ctrl.abort()
      clearInterval(id)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, intervalMs, tick, enabled])

  return { data, error, refresh: () => setTick((t) => t + 1) }
}

/** Re-render every `ms` so countdowns tick between polls. */
export function useNow(ms = 250) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(id)
  }, [ms])
  return now
}
