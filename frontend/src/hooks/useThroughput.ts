import { useState } from 'react'
import type { QueueSnapshot } from '../api/types'

const WINDOW_MS = 10 * 60_000 // look at the last 10 minutes
const MIN_SPAN_MS = 30_000 // need at least 30 s of history...
const MIN_DONE = 2 // ...and 2 finished turns before trusting the measurement

type Sample = { t: number; done: number }
type Tracker = { key: string | null; at: string | null; samples: Sample[] }

export interface Throughput {
  /** turns finished per minute */
  perMinute: number
  /** true = measured from live data; false = worst case (everyone uses the whole window) */
  measured: boolean
}

/**
 * How fast the line moves. Every snapshot carries the running totals of confirmed + expired
 * entries, so the slope of that counter over time is "turns finished per minute".
 * Until there's enough history, fall back to the worst case: every slot uses its full window.
 * Uses the snapshot's server timestamp, so this stays a pure function of props.
 */
export function useThroughput(waitlistId: string | null, snap: QueueSnapshot | null | undefined,
                              windowSeconds: number): Throughput | null {
  const [tr, setTr] = useState<Tracker>({ key: null, at: null, samples: [] })

  // "previous props in state": record a sample whenever a new snapshot arrives
  if (snap && waitlistId && (tr.key !== waitlistId || tr.at !== snap.at)) {
    const t = Date.parse(snap.at)
    const done = snap.confirmedCount + snap.expiredCount
    const base = tr.key === waitlistId ? tr.samples : []
    setTr({ key: waitlistId, at: snap.at, samples: [...base, { t, done }].filter((x) => t - x.t <= WINDOW_MS) })
  }

  if (!snap || !waitlistId) return null
  const s = tr.key === waitlistId ? tr.samples : []
  const first = s[0]
  const last = s[s.length - 1]
  if (first && last && last.t - first.t >= MIN_SPAN_MS && last.done - first.done >= MIN_DONE) {
    return { perMinute: ((last.done - first.done) / (last.t - first.t)) * 60_000, measured: true }
  }
  return { perMinute: snap.servingCapacity / Math.max(1 / 60, windowSeconds / 60), measured: false }
}
