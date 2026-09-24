import { motion } from 'motion/react'
import { useState } from 'react'
import { Hint } from './Hint'

/** part="join": the customer-facing join box. part="load": the demo load tools (simulate, spam). */
export function Controls({
  part,
  disabled,
  meName,
  onJoin,
  onJoinAsMe,
  onBurst,
  onHammer,
}: {
  part: 'join' | 'load'
  disabled: boolean
  meName?: string
  onJoin: (name: string, groupSize: number) => Promise<void>
  onJoinAsMe: () => Promise<void>
  onBurst: (n: number) => Promise<void>
  onHammer: () => Promise<void>
}) {
  const [name, setName] = useState('')
  const [groupSize, setGroupSize] = useState(1)
  const [busy, setBusy] = useState(false)

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } finally {
      setBusy(false)
    }
  }

  if (part === 'load') {
    return (
      <section className="card" aria-labelledby="load-title">
        <h2 id="load-title">
          Load
          <Hint>
            Tools for putting pressure on the queue during a demo. Every click makes real API calls, same as a real
            user would.
          </Hint>
        </h2>
        <div className="burst">
          <span className="muted">
            Simulate
            <Hint>
              Adds 1, 10 or 50 made-up people at once, so you can watch the line fill up and move without typing names.
              They&apos;re real joins, just with random names.
            </Hint>
          </span>
          {[1, 10, 50].map((n) => (
            <motion.button key={n} whileHover={{ y: -2 }} whileTap={{ scale: 0.92 }} className="btn"
              disabled={disabled || busy} onClick={() => void run(() => onBurst(n))}>
              +{n}
            </motion.button>
          ))}
        </div>
        <div className="spam-row">
          <motion.button whileTap={{ scale: 0.97 }} className="btn btn-block btn-warn" disabled={disabled || busy}
            onClick={() => void run(onHammer)}>
            Spam 30 requests
          </motion.button>
          <Hint>
            Fires 30 requests at once as a single user. The rate limiter allows 20 writes per 10 seconds, so the rest
            come back as <b>429 Too Many Requests</b>. This is what stops one person from flooding the queue.
          </Hint>
        </div>
        <p className="hint">Each join is a real <code>POST /entries</code> with its own JWT.</p>
      </section>
    )
  }

  return (
    <section className="card" aria-labelledby="controls-title">
      <h2 id="controls-title">
        Join
        <Hint>
          Puts someone at the back of this waitlist. Each join is a real API call: a guest account is created and joins
          with its own login token. The <b>alone / +1 / +2 / +3</b> picker joins a group of friends together.
        </Hint>
      </h2>
      {meName && (
        <motion.button whileHover={{ y: -1 }} whileTap={{ scale: 0.97 }} className="btn btn-primary btn-block join-me"
          disabled={disabled || busy} onClick={() => void run(onJoinAsMe)}>
          Join as {meName}
        </motion.button>
      )}
      <form
        className="join-form"
        onSubmit={(e) => {
          e.preventDefault()
          const n = name.trim()
          if (!n) return
          void run(async () => {
            await onJoin(n, groupSize)
            setName('')
          })
        }}
      >
        <input aria-label="Name" placeholder={meName ? 'or someone else…' : 'Name'} value={name} maxLength={100}
          onChange={(e) => setName(e.target.value)} disabled={disabled || busy} />
        <select aria-label="Group size" value={groupSize} onChange={(e) => setGroupSize(Number(e.target.value))}>
          <option value={1}>alone</option>
          <option value={2}>+1</option>
          <option value={3}>+2</option>
          <option value={4}>+3</option>
        </select>
        <motion.button whileTap={{ scale: 0.95 }} className="btn btn-primary" disabled={disabled || busy || !name.trim()}>
          Join
        </motion.button>
      </form>
    </section>
  )
}
