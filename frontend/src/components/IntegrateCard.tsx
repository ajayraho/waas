import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import { Hint } from './Hint'

/**
 * What a business pastes into its own site to use this waitlist: sign a customer in, join,
 * then subscribe to their live position. Real endpoints, with this waitlist's id filled in.
 */
export function IntegrateCard({ waitlistId }: { waitlistId: string }) {
  const [copied, setCopied] = useState(false)
  const origin = window.location.origin
  const ws = origin.replace(/^http/, 'ws')

  const snippet = `// 1. a token for your customer (or bring your own users)
const { token } = await fetch('${origin}/api/auth/guest', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ name: 'Ada' }),
}).then((r) => r.json())

// 2. join the line (safe to retry: send an Idempotency-Key)
const entry = await fetch('${origin}/api/waitlists/${waitlistId}/entries', {
  method: 'POST',
  headers: { Authorization: \`Bearer \${token}\`, 'Idempotency-Key': crypto.randomUUID() },
}).then((r) => r.json())

// 3. live position, pushed over STOMP (@stomp/stompjs)
const client = new Client({ brokerURL: '${ws}/ws' })
client.onConnect = () =>
  client.subscribe(\`/topic/waitlists/${waitlistId}/entries/\${entry.entryId}\`,
    (m) => console.log(JSON.parse(m.body))) // { state, position, queueSize, expiresAt }
client.activate()`

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(snippet)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked (http, permissions): the code is selectable anyway */
    }
  }

  return (
    <section className="card" aria-labelledby="integrate-title">
      <h2 id="integrate-title">
        Integrate
        <Hint>
          How a business plugs this waitlist into its own website or app: two HTTP calls, then any STOMP client for
          live updates. The ids and URLs are real, so this code works against this running instance.
        </Hint>
      </h2>
      <div className="snippet">
        <div className="snippet-head">
          <span className="mono muted">javascript · your site</span>
          <button className="btn btn-sm snippet-copy" onClick={() => void copy()}>
            <AnimatePresence mode="wait" initial={false}>
              <motion.span key={copied ? 'y' : 'n'} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }}>
                {copied ? 'copied' : 'copy'}
              </motion.span>
            </AnimatePresence>
          </button>
        </div>
        <pre>
          <code>{snippet}</code>
        </pre>
      </div>
      <p className="hint">
        See it working: <a className="link-btn" href={`/shop.html?w=${waitlistId}`} target="_blank" rel="noreferrer">a tiny shop page</a>{' '}
        built from just this code (one HTML file, no libraries). Open it in a few tabs and watch them move here.
      </p>
    </section>
  )
}
