import { motion } from 'motion/react'
import { useState } from 'react'

export function Controls({
  disabled,
  meName,
  onJoin,
  onJoinAsMe,
  onBurst,
  onHammer,
}: {
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

  return (
    <section className="card" aria-labelledby="controls-title">
      <h2 id="controls-title">Join</h2>
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
      <div className="burst">
        <span className="muted">Simulate</span>
        {[1, 10, 50].map((n) => (
          <motion.button key={n} whileHover={{ y: -2 }} whileTap={{ scale: 0.92 }} className="btn"
            disabled={disabled || busy} onClick={() => void run(() => onBurst(n))}>
            +{n}
          </motion.button>
        ))}
      </div>
      <motion.button whileTap={{ scale: 0.97 }} className="btn btn-block btn-warn" disabled={disabled || busy}
        onClick={() => void run(onHammer)} title="30 writes at once as one user: the rate limiter kicks in after 20">
        Spam 30 requests
      </motion.button>
      <p className="hint">Each join is a real <code>POST /entries</code> with its own JWT.</p>
    </section>
  )
}
