import { useState } from 'react'
import type { Waitlist } from '../api/types'
import { tenant } from '../lib/tenant'
import { Hint } from './Hint'

const WINDOWS = [
  { label: '15 s', value: 15 },
  { label: '30 s', value: 30 },
  { label: '1 min', value: 60 },
  { label: '5 min', value: 300 },
  { label: '10 min', value: 600 },
  { label: '15 min', value: 900 },
]

/**
 * Tenant-admin knobs (§4): serving capacity and the checkout window.
 * The parent keys this component on the saved config, so a save or a tab switch remounts
 * it with fresh form state (no syncing effect needed).
 */
export function ConfigCard({
  waitlist,
  onSave,
}: {
  waitlist: Waitlist
  onSave: (apiKey: string, patch: { servingCapacity: number; reservationWindowSeconds: number }) => Promise<void>
}) {
  const [capacity, setCapacity] = useState(waitlist.servingCapacity)
  const [windowSec, setWindowSec] = useState(waitlist.reservationWindowSeconds)
  const [busy, setBusy] = useState(false)
  const [apiKey, setApiKey] = useState(tenant.key) // the business key from the directory page

  const dirty = capacity !== waitlist.servingCapacity || windowSec !== waitlist.reservationWindowSeconds
  const windows = WINDOWS.some((w) => w.value === windowSec)
    ? WINDOWS
    : [...WINDOWS, { label: `${windowSec} s`, value: windowSec }].sort((a, b) => a.value - b.value)

  return (
    <section className="card" aria-labelledby="config-title">
      <h2 id="config-title">
        Waitlist config
        <Hint>Settings the business that owns this waitlist can change while it&apos;s running.</Hint>
      </h2>
      <div className="config-row">
        <span>
          <label htmlFor="capacity">Serving slots</label>
          <Hint>How many people can hold a turn at the same time. More slots means the line moves faster.</Hint>
        </span>
        <div className="stepper">
          <button className="btn btn-sm" aria-label="Fewer slots" disabled={capacity <= 1} onClick={() => setCapacity((c) => c - 1)}>
            −
          </button>
          <output id="capacity" className="stepper-value">{capacity}</output>
          <button className="btn btn-sm" aria-label="More slots" disabled={capacity >= 20} onClick={() => setCapacity((c) => c + 1)}>
            +
          </button>
        </div>
      </div>
      <div className="config-row">
        <span>
          <label htmlFor="window">Checkout window</label>
          <Hint>
            How long someone has to confirm once it&apos;s their turn. If the time runs out they&apos;re marked
            expired and the slot goes to the next person.
          </Hint>
        </span>
        <select id="window" value={windowSec} onChange={(e) => setWindowSec(Number(e.target.value))}>
          {windows.map((w) => (
            <option key={w.value} value={w.value}>
              {w.label}
            </option>
          ))}
        </select>
      </div>
      <div className="config-row">
        <span>
          <label htmlFor="apikey">Tenant API key</label>
          <Hint>
            Proves you&apos;re the business that owns this waitlist. A key from a different business gets <b>404</b>,
            not 403, so other businesses can&apos;t even tell this waitlist exists.
          </Hint>
        </span>
        <input id="apikey" className="key-input mono" value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
      </div>
      <button
        className="btn btn-primary btn-block"
        disabled={!dirty || busy}
        onClick={async () => {
          setBusy(true)
          try {
            await onSave(apiKey, { servingCapacity: capacity, reservationWindowSeconds: windowSec })
          } finally {
            setBusy(false)
          }
        }}
      >
        Apply
      </button>
      <p className="hint">Admin only: the key must belong to the tenant that owns this waitlist (try a wrong one → 404).</p>
    </section>
  )
}
